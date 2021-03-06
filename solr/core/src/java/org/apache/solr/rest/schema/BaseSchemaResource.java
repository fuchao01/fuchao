package org.apache.solr.rest.schema;
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.response.BinaryQueryResponseWriter;
import org.apache.solr.response.QueryResponseWriter;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.servlet.ResponseUtils;
import org.apache.solr.util.FastWriter;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.representation.OutputRepresentation;
import org.restlet.resource.ResourceException;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLDecoder;
import java.nio.charset.Charset;


/**
 * Base class of all Solr Schema Restlet resource classes.
 */
abstract class BaseSchemaResource extends ServerResource {
  private static final Charset UTF8 = Charset.forName("UTF-8");
  protected static final String SHOW_DEFAULTS = "showDefaults";


  private SolrCore solrCore;
  private IndexSchema schema;
  private SolrQueryRequest solrRequest;
  private SolrQueryResponse solrResponse;
  private QueryResponseWriter responseWriter;
  private String contentType;
  private boolean doIndent;

  protected SolrCore getSolrCore() { return solrCore; }
  protected IndexSchema getSchema() { return schema; }
  protected SolrQueryRequest getSolrRequest() { return solrRequest; }
  protected SolrQueryResponse getSolrResponse() { return solrResponse; }
  protected String getContentType() { return contentType; }


  protected BaseSchemaResource() {
    super();
    doIndent = true; // default to indenting
  }

  /**
   * Pulls the SolrQueryRequest constructed in SolrDispatchFilter
   * from the SolrRequestInfo thread local, then gets the SolrCore
   * and IndexSchema and sets up the response.
   * writer.
   * <p/>
   * If an error occurs during initialization, setExisting(false) is
   * called and an error status code and message is set; in this case,
   * Restlet will not continue servicing the request (by calling the
   * method annotated to associate it with GET, etc., but rather will
   * send an error response.
   */
  @Override
  public void doInit() throws ResourceException {
    super.doInit();
    setNegotiated(false); // Turn off content negotiation for now
    if (isExisting()) {
      try {
        SolrRequestInfo solrRequestInfo = SolrRequestInfo.getRequestInfo();
        if (null == solrRequestInfo) {
          final String message = "No handler or core found in " + getRequest().getOriginalRef().getPath();
          doError(Status.CLIENT_ERROR_BAD_REQUEST, message);
          setExisting(false);
        } else {
          solrRequest = solrRequestInfo.getReq();
          if (null == solrRequest) {
            final String message = "No handler or core found in " + getRequest().getOriginalRef().getPath();
            doError(Status.CLIENT_ERROR_BAD_REQUEST, message);
            setExisting(false);
          } else {
            solrResponse = solrRequestInfo.getRsp();
            solrCore = solrRequest.getCore();
            schema = solrRequest.getSchema();
            String responseWriterName = solrRequest.getParams().get(CommonParams.WT);
            if (null == responseWriterName) {
              responseWriterName = "json"; // Default to json writer
            }
            String indent = solrRequest.getParams().get("indent");
            if (null != indent && ("".equals(indent) || "off".equals(indent))) {
              doIndent = false;
            } else {                       // indent by default
              ModifiableSolrParams newParams = new ModifiableSolrParams(solrRequest.getParams());
              newParams.remove(indent);
              newParams.add("indent", "on");
              solrRequest.setParams(newParams);
            }
            responseWriter = solrCore.getQueryResponseWriter(responseWriterName);
            contentType = responseWriter.getContentType(solrRequest, solrResponse);
            final String path = getRequest().getRootRef().getPath();
            if ( ! "/schema".equals(path)) { 
              // don't set webapp property on the request when context and core/collection are excluded 
              final int cutoffPoint = path.indexOf("/", 1);
              final String firstPathElement = -1 == cutoffPoint ? path : path.substring(0, cutoffPoint);
              solrRequest.getContext().put("webapp", firstPathElement); // Context path
            }
            SolrCore.preDecorateResponse(solrRequest, solrResponse);
          }
        }
      } catch (Throwable t) {
        setExisting(false);
        throw new ResourceException(t);
      }
    }
  }

  /**
   * This class serves as an adapter between Restlet and Solr's response writers. 
   */
  public class SolrOutputRepresentation extends OutputRepresentation {
    
    SolrOutputRepresentation() {
      // No normalization, in case of a custom media type
      super(MediaType.valueOf(contentType));
      // TODO: For now, don't send the Vary: header, but revisit if/when content negotiation is added
      getDimensions().clear();
    }
    
    
    /** Called by Restlet to get the response body */
    @Override
    public void write(OutputStream outputStream) throws IOException {
      if (getRequest().getMethod() != Method.HEAD) {
        if (responseWriter instanceof BinaryQueryResponseWriter) {
          BinaryQueryResponseWriter binWriter = (BinaryQueryResponseWriter)responseWriter;
          binWriter.write(outputStream, solrRequest, solrResponse);
        } else {
          String charset = ContentStreamBase.getCharsetFromContentType(contentType);
          Writer out = (charset == null || charset.equalsIgnoreCase("UTF-8"))
              ? new OutputStreamWriter(outputStream, UTF8)
              : new OutputStreamWriter(outputStream, charset);
          out = new FastWriter(out);
          responseWriter.write(out, solrRequest, solrResponse);
          out.flush();
        }
      }
    }
  }

  /**
   * Deal with an exception on the SolrResponse, fill in response header info,
   * and log the accumulated messages on the SolrResponse.
   */
  protected void handlePostExecution(Logger log) {
    
    handleException(log);
    
    // TODO: should status=0 (success?) be left as-is in the response header?
    SolrCore.postDecorateResponse(null, solrRequest, solrResponse);

    if (log.isInfoEnabled() && solrResponse.getToLog().size() > 0) {
      log.info(solrResponse.getToLogAsString(solrCore.getLogId()));
    }
  }

  /**
   * If there is an exception on the SolrResponse:
   * <ul>
   *   <li>error info is added to the SolrResponse;</li>
   *   <li>the response status code is set to the error code from the exception; and</li>
   *   <li>the exception message is added to the list of things to be logged.</li>
   * </ul>
   */
  protected void handleException(Logger log) {
    Exception exception = getSolrResponse().getException();
    if (null != exception) {
      NamedList info = new SimpleOrderedMap();
      int code = ResponseUtils.getErrorInfo(exception, info, log);
      setStatus(Status.valueOf(code));
      getSolrResponse().add("error", info);
      String message = (String)info.get("msg");
      if (null != message && ! message.trim().isEmpty()) {
        getSolrResponse().getToLog().add("msg", "{" + message.trim() + "}");
      }
    }
  }

  /** Decode URL-encoded strings as UTF-8, and avoid converting "+" to space */
  protected static String urlDecode(String str) throws UnsupportedEncodingException {
    return URLDecoder.decode(str.replace("+", "%2B"), "UTF-8");
  }
}
