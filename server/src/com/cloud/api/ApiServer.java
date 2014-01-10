// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.naming.ConfigurationException;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpServerConnection;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.SocketHttpServerConnection;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpRequestHandlerRegistry;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.acl.APIChecker;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.BaseAsyncCreateCmd;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.host.ListHostsCmd;
import org.apache.cloudstack.api.command.admin.router.ListRoutersCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStoragePoolsCmd;
import org.apache.cloudstack.api.command.admin.user.ListUsersCmd;
import org.apache.cloudstack.api.command.user.account.ListAccountsCmd;
import org.apache.cloudstack.api.command.user.account.ListProjectAccountsCmd;
import org.apache.cloudstack.api.command.user.event.ListEventsCmd;
import org.apache.cloudstack.api.command.user.offering.ListDiskOfferingsCmd;
import org.apache.cloudstack.api.command.user.offering.ListServiceOfferingsCmd;
import org.apache.cloudstack.api.command.user.project.ListProjectInvitationsCmd;
import org.apache.cloudstack.api.command.user.project.ListProjectsCmd;
import org.apache.cloudstack.api.command.user.securitygroup.ListSecurityGroupsCmd;
import org.apache.cloudstack.api.command.user.tag.ListTagsCmd;
import org.apache.cloudstack.api.command.user.vm.ListVMsCmd;
import org.apache.cloudstack.api.command.user.vmgroup.ListVMGroupsCmd;
import org.apache.cloudstack.api.command.user.volume.ListVolumesCmd;
import org.apache.cloudstack.api.command.user.zone.ListZonesCmd;
import org.apache.cloudstack.api.response.AsyncJobResponse;
import org.apache.cloudstack.api.response.CreateCmdResponse;
import org.apache.cloudstack.api.response.ExceptionResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.impl.ConfigurationVO;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;

import com.cloud.api.response.ApiResponseSerializer;
import com.cloud.configuration.Config;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.event.ActionEventUtils;
import com.cloud.exception.AccountLimitException;
import com.cloud.exception.CloudAuthenticationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.RequestLimitException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.DomainManager;
import com.cloud.user.User;
import com.cloud.user.UserAccount;
import com.cloud.user.UserVO;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExceptionProxyObject;

@Component
public class ApiServer extends ManagerBase implements HttpRequestHandler, ApiServerService {
    private static final Logger s_logger = Logger.getLogger(ApiServer.class.getName());
    private static final Logger s_accessLogger = Logger.getLogger("apiserver." + ApiServer.class.getName());

    public static boolean encodeApiResponse = false;
    public static String jsonContentType = "text/javascript";
    @Inject ApiDispatcher _dispatcher;

    @Inject private AccountManager _accountMgr;
    @Inject private DomainManager _domainMgr;
    @Inject
    private AsyncJobManager _asyncMgr;
    @Inject private ConfigurationDao _configDao;
    @Inject
    private EntityManager _entityMgr;

    List<PluggableService> _pluggableServices;
    List<APIChecker> _apiAccessCheckers;

    @Inject
    protected ApiAsyncJobDispatcher _asyncDispatcher;
    private static int _workerCount = 0;
    private static final DateFormat _dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
    private static Map<String, List<Class<?>>> _apiNameCmdClassMap = new HashMap<String, List<Class<?>>>();

    private static ExecutorService _executor = new ThreadPoolExecutor(10, 150, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new NamedThreadFactory("ApiServer"));

    public ApiServer() {
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        return true;
    }

    @Override
    public boolean start() {
        Integer apiPort = null; // api port, null by default
        SearchCriteria<ConfigurationVO> sc = _configDao.createSearchCriteria();
        sc.addAnd("name", SearchCriteria.Op.EQ, Config.IntegrationAPIPort.key());
        List<ConfigurationVO> values = _configDao.search(sc, null);
        if ((values != null) && (values.size() > 0)) {
            ConfigurationVO apiPortConfig = values.get(0);
            if (apiPortConfig.getValue() != null) {
                apiPort = Integer.parseInt(apiPortConfig.getValue());
            }
        }

        Map<String, String> configs = _configDao.getConfiguration();
        String strSnapshotLimit = configs.get(Config.ConcurrentSnapshotsThresholdPerHost.key());
        if (strSnapshotLimit != null) {
            Long snapshotLimit = NumbersUtil.parseLong(strSnapshotLimit, 1L);
            if (snapshotLimit.longValue() <= 0) {
                s_logger.debug("Global config parameter " + Config.ConcurrentSnapshotsThresholdPerHost.toString()
                        + " is less or equal 0; defaulting to unlimited");
            } else {
                _dispatcher.setCreateSnapshotQueueSizeLimit(snapshotLimit);
            }
        }

        Set<Class<?>> cmdClasses = new HashSet<Class<?>>();
        for(PluggableService pluggableService: _pluggableServices) {
            cmdClasses.addAll(pluggableService.getCommands());
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Discovered plugin " + pluggableService.getClass().getSimpleName());
            }
        }

        for(Class<?> cmdClass: cmdClasses) {
            APICommand at = cmdClass.getAnnotation(APICommand.class);
            if (at == null) {
                throw new CloudRuntimeException(String.format("%s is claimed as a API command, but it doesn't have @APICommand annotation", cmdClass.getName()));
            }
            String apiName = at.name();
            List<Class<?>> apiCmdList = _apiNameCmdClassMap.get(apiName);
            if (apiCmdList == null) {
                apiCmdList = new ArrayList<Class<?>>();
                _apiNameCmdClassMap.put(apiName, apiCmdList);
            }
            apiCmdList.add(cmdClass);
        }

        encodeApiResponse = Boolean.valueOf(_configDao.getValue(Config.EncodeApiResponse.key()));
        String jsonType = _configDao.getValue(Config.JavaScriptDefaultContentType.key());
        if (jsonType != null) {
            jsonContentType = jsonType;
        }

        if (apiPort != null) {
            ListenerThread listenerThread = new ListenerThread(this, apiPort);
            listenerThread.start();
        }
        
        return true;
    }

    // NOTE: handle() only handles over the wire (OTW) requests from integration.api.port 8096
    // If integration api port is not configured, actual OTW requests will be received by ApiServlet
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext context)
            throws HttpException, IOException {

        // Create StringBuffer to log information in access log
        StringBuffer sb = new StringBuffer();
        HttpServerConnection connObj = (HttpServerConnection) context.getAttribute("http.connection");
        if (connObj instanceof SocketHttpServerConnection) {
            InetAddress remoteAddr = ((SocketHttpServerConnection) connObj).getRemoteAddress();
            sb.append(remoteAddr.toString() + " -- ");
        }
        sb.append(StringUtils.cleanString(request.getRequestLine().toString()));

        try {
            List<NameValuePair> paramList = null;
            try {
                paramList = URLEncodedUtils.parse(new URI(request.getRequestLine().getUri()), "UTF-8");
            } catch (URISyntaxException e) {
                s_logger.error("Error parsing url request", e);
            }

            // Use Multimap as the parameter map should be in the form (name=String, value=String[])
            // So parameter values are stored in a list for the same name key
            // APITODO: Use Guava's (import com.google.common.collect.Multimap;)
            // (Immutable)Multimap<String, String> paramMultiMap = HashMultimap.create();
            // Map<String, Collection<String>> parameterMap = paramMultiMap.asMap();
            Map parameterMap = new HashMap<String, String[]>();
            String responseType = BaseCmd.RESPONSE_TYPE_XML;
            for (NameValuePair param : paramList) {
                if (param.getName().equalsIgnoreCase("response")) {
                    responseType = param.getValue();
                    continue;
                }
                parameterMap.put(param.getName(), new String[] { param.getValue() });
            }

            // Get the type of http method being used.
            parameterMap.put("httpmethod", new String[] { request.getRequestLine().getMethod() });

            // Check responseType, if not among valid types, fallback to JSON
            if (!(responseType.equals(BaseCmd.RESPONSE_TYPE_JSON) || responseType.equals(BaseCmd.RESPONSE_TYPE_XML))) {
                responseType = BaseCmd.RESPONSE_TYPE_XML;
            }

            try {
                // always trust commands from API port, user context will always be UID_SYSTEM/ACCOUNT_ID_SYSTEM
                CallContext.register(_accountMgr.getSystemUser(), _accountMgr.getSystemAccount());
                sb.insert(0, "(userId=" + User.UID_SYSTEM + " accountId=" + Account.ACCOUNT_ID_SYSTEM + " sessionId=" + null + ") ");
                String responseText = handleRequest(parameterMap, responseType, sb);
                sb.append(" 200 " + ((responseText == null) ? 0 : responseText.length()));

                writeResponse(response, responseText, HttpStatus.SC_OK, responseType, null);
            } catch (ServerApiException se) {
                String responseText = getSerializedApiError(se, parameterMap, responseType);
                writeResponse(response, responseText, se.getErrorCode().getHttpCode(), responseType, se.getDescription());
                sb.append(" " + se.getErrorCode() + " " + se.getDescription());
            } catch (RuntimeException e) {
                // log runtime exception like NullPointerException to help identify the source easier
                s_logger.error("Unhandled exception, ", e);
                throw e;
            }
        } finally {
            s_accessLogger.info(sb.toString());
            CallContext.unregister();
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public String handleRequest(Map params, String responseType, StringBuffer auditTrailSb) throws ServerApiException {
        String response = null;
        String[] command = null;

        try {
            command = (String[]) params.get("command");
            if (command == null) {
                s_logger.error("invalid request, no command sent");
                if (s_logger.isTraceEnabled()) {
                    s_logger.trace("dumping request parameters");
                    for (Object key : params.keySet()) {
                        String keyStr = (String) key;
                        String[] value = (String[]) params.get(key);
                        s_logger.trace("   key: " + keyStr + ", value: " + ((value == null) ? "'null'" : value[0]));
                    }
                }
                throw new ServerApiException(ApiErrorCode.UNSUPPORTED_ACTION_ERROR, "Invalid request, no command sent");
            } else {
                Map<String, String> paramMap = new HashMap<String, String>();
                Set keys = params.keySet();
                Iterator keysIter = keys.iterator();
                while (keysIter.hasNext()) {
                    String key = (String) keysIter.next();
                    if ("command".equalsIgnoreCase(key)) {
                        continue;
                    }
                    String[] value = (String[]) params.get(key);
                    // fail if parameter value contains ASCII control (non-printable) characters
                    if (value[0] != null) {
                        String newValue = StringUtils.stripControlCharacters(value[0]);
                        if ( !newValue.equals(value[0]) ) {
                            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Received value " + value[0] + " for parameter "
                                    + key + " is invalid, contains illegal ASCII non-printable characters");
                        }
                    }
                    paramMap.put(key, value[0]);
                }

                Class<?> cmdClass = getCmdClass(command[0]);
                if (cmdClass != null) {
                    BaseCmd cmdObj = (BaseCmd) cmdClass.newInstance();
                    cmdObj = ComponentContext.inject(cmdObj);
                    cmdObj.configure();
                    cmdObj.setFullUrlParams(paramMap);
                    cmdObj.setResponseType(responseType);
                    cmdObj.setHttpMethod(paramMap.get("httpmethod").toString());

                    // This is where the command is either serialized, or directly dispatched
                    response = queueCommand(cmdObj, paramMap);
                    buildAuditTrail(auditTrailSb, command[0], response);
                } else {
                    if (!command[0].equalsIgnoreCase("login") && !command[0].equalsIgnoreCase("logout")) {
                        String errorString = "Unknown API command: " + ((command == null) ? "null" : command[0]);
                        s_logger.warn(errorString);
                        auditTrailSb.append(" " + errorString);
                        throw new ServerApiException(ApiErrorCode.UNSUPPORTED_ACTION_ERROR, errorString);
                    }
                }
            }
        } catch (InvalidParameterValueException ex){
            s_logger.info(ex.getMessage());
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex){
            s_logger.info(ex.getMessage());
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, ex.getMessage(), ex);
        } catch (PermissionDeniedException ex){
            ArrayList<ExceptionProxyObject> idList = ex.getIdProxyList();
            if (idList != null) {
                StringBuffer buf = new StringBuffer();
                for (ExceptionProxyObject obj : idList){
                    buf.append(obj.getDescription());
                    buf.append(":");
                    buf.append(obj.getUuid());
                    buf.append(" ");
                }
                s_logger.info("PermissionDenied: " + ex.getMessage() + " on objs: [" + buf.toString() + "]");
            } else {
                s_logger.info("PermissionDenied: " + ex.getMessage());
            }
            throw new ServerApiException(ApiErrorCode.ACCOUNT_ERROR, ex.getMessage(), ex);
        } catch (AccountLimitException ex){
            s_logger.info(ex.getMessage());
            throw new ServerApiException(ApiErrorCode.ACCOUNT_RESOURCE_LIMIT_ERROR, ex.getMessage(), ex);
        } catch (InsufficientCapacityException ex){
            s_logger.info(ex.getMessage());
            String errorMsg = ex.getMessage();
            if (!_accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())) {
                // hide internal details to non-admin user for security reason
                errorMsg = BaseCmd.USER_ERROR_MESSAGE;
            }
            throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, errorMsg, ex);
        } catch (ResourceAllocationException ex){
            s_logger.info(ex.getMessage());
            throw new ServerApiException(ApiErrorCode.RESOURCE_ALLOCATION_ERROR, ex.getMessage(), ex);
        } catch (ResourceUnavailableException ex){
            s_logger.info(ex.getMessage());
            String errorMsg = ex.getMessage();
            if (!_accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())) {
                // hide internal details to non-admin user for security reason
                errorMsg = BaseCmd.USER_ERROR_MESSAGE;
            }
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, errorMsg, ex);
        } catch (ServerApiException ex) {
            s_logger.info(ex.getDescription());
            throw ex;
        } catch (Exception ex){
            s_logger.error("unhandled exception executing api command: " + ((command == null) ? "null" : command[0]), ex);
            String errorMsg = ex.getMessage();
            if (!_accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())) {
                // hide internal details to non-admin user for security reason
                errorMsg = BaseCmd.USER_ERROR_MESSAGE;
            }
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, errorMsg, ex);
        }

        return response;
    }

    private String getBaseAsyncResponse(long jobId, BaseAsyncCmd cmd) {
        AsyncJobResponse response = new AsyncJobResponse();

        AsyncJob job = _entityMgr.findById(AsyncJob.class, jobId);
        response.setJobId(job.getUuid());
        response.setResponseName(cmd.getCommandName());
        return ApiResponseSerializer.toSerializedString(response, cmd.getResponseType());
    }

    private String getBaseAsyncCreateResponse(long jobId, BaseAsyncCreateCmd cmd, String objectUuid) {
        CreateCmdResponse response = new CreateCmdResponse();
        AsyncJob job = _entityMgr.findById(AsyncJob.class, jobId);
        response.setJobId(job.getUuid());
        response.setId(objectUuid);
        response.setResponseName(cmd.getCommandName());
        return ApiResponseSerializer.toSerializedString(response, cmd.getResponseType());
    }

    private String queueCommand(BaseCmd cmdObj, Map<String, String> params) throws Exception {
        CallContext ctx = CallContext.current();
        Long callerUserId = ctx.getCallingUserId();
        Account caller = ctx.getCallingAccount();


        // Queue command based on Cmd super class:
        // BaseCmd: cmd is dispatched to ApiDispatcher, executed, serialized and returned.
        // BaseAsyncCreateCmd: cmd params are processed and create() is called, then same workflow as BaseAsyncCmd.
        // BaseAsyncCmd: cmd is processed and submitted as an AsyncJob, job related info is serialized and returned.
        if (cmdObj instanceof BaseAsyncCmd) {
            Long objectId = null;
            String objectUuid = null;
            if (cmdObj instanceof BaseAsyncCreateCmd) {
                BaseAsyncCreateCmd createCmd = (BaseAsyncCreateCmd) cmdObj;
                _dispatcher.dispatchCreateCmd(createCmd, params);
                objectId = createCmd.getEntityId();
                objectUuid = createCmd.getEntityUuid();
                params.put("id", objectId.toString());
            } else {
                ApiDispatcher.processParameters(cmdObj, params);
            }

            BaseAsyncCmd asyncCmd = (BaseAsyncCmd) cmdObj;

            if (callerUserId != null) {
                params.put("ctxUserId", callerUserId.toString());
            }
            if (caller != null) {
                params.put("ctxAccountId", String.valueOf(caller.getId()));
            }

            long startEventId = ctx.getStartEventId();
            asyncCmd.setStartEventId(startEventId);

            // save the scheduled event
            Long eventId = ActionEventUtils.onScheduledActionEvent((callerUserId == null) ? User.UID_SYSTEM : callerUserId,
                    asyncCmd.getEntityOwnerId(), asyncCmd.getEventType(), asyncCmd.getEventDescription(),
                    startEventId);
            if (startEventId == 0) {
                // There was no create event before, set current event id as start eventId
                startEventId = eventId;
            }

            params.put("ctxStartEventId", String.valueOf(startEventId));
            params.put("cmdEventType", asyncCmd.getEventType().toString());

            Long instanceId = (objectId == null) ? asyncCmd.getInstanceId() : objectId;
            AsyncJobVO job = new AsyncJobVO(ctx.getContextId(), callerUserId, caller.getId(), cmdObj.getClass().getName(),
                    ApiGsonHelper.getBuilder().create().toJson(params), instanceId,
                    asyncCmd.getInstanceType() != null ? asyncCmd.getInstanceType().toString() : null);
            job.setDispatcher(_asyncDispatcher.getName());

            long jobId = _asyncMgr.submitAsyncJob(job);

            if (jobId == 0L) {
                String errorMsg = "Unable to schedule async job for command " + job.getCmd();
                s_logger.warn(errorMsg);
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, errorMsg);
            }

            if (objectId != null) {
                String objUuid = (objectUuid == null) ? objectId.toString() : objectUuid;
                return getBaseAsyncCreateResponse(jobId, (BaseAsyncCreateCmd)asyncCmd, objUuid);
            } else {
                SerializationContext.current().setUuidTranslation(true);
                return getBaseAsyncResponse(jobId, asyncCmd);
            }
        } else {
            _dispatcher.dispatch(cmdObj, params, false);

            // if the command is of the listXXXCommand, we will need to also return the
            // the job id and status if possible
            // For those listXXXCommand which we have already created DB views, this step is not needed since async job is joined in their db views.
            if (cmdObj instanceof BaseListCmd && !(cmdObj instanceof ListVMsCmd) && !(cmdObj instanceof ListRoutersCmd)
                    && !(cmdObj instanceof ListSecurityGroupsCmd)
                    && !(cmdObj instanceof ListTagsCmd)
                    && !(cmdObj instanceof ListEventsCmd)
                    && !(cmdObj instanceof ListVMGroupsCmd)
                    && !(cmdObj instanceof ListProjectsCmd)
                    && !(cmdObj instanceof ListProjectAccountsCmd)
                    && !(cmdObj instanceof ListProjectInvitationsCmd)
                    && !(cmdObj instanceof ListHostsCmd)
                    && !(cmdObj instanceof ListVolumesCmd)
                    && !(cmdObj instanceof ListUsersCmd)
                    && !(cmdObj instanceof ListAccountsCmd)
                    && !(cmdObj instanceof ListStoragePoolsCmd)
                    && !(cmdObj instanceof ListDiskOfferingsCmd)
                    && !(cmdObj instanceof ListServiceOfferingsCmd)
                    && !(cmdObj instanceof ListZonesCmd)
                    ) {
                buildAsyncListResponse((BaseListCmd) cmdObj, caller);
            }

            SerializationContext.current().setUuidTranslation(true);
            return ApiResponseSerializer.toSerializedString((ResponseObject) cmdObj.getResponseObject(), cmdObj.getResponseType());
        }
    }

    @SuppressWarnings("unchecked")
    private void buildAsyncListResponse(BaseListCmd command, Account account) {
        List<ResponseObject> responses = ((ListResponse) command.getResponseObject()).getResponses();
        if (responses != null && responses.size() > 0) {
            List<? extends AsyncJob> jobs = null;

            // list all jobs for ROOT admin
            if (_accountMgr.isRootAdmin(account.getId())) {
                jobs = _asyncMgr.findInstancePendingAsyncJobs(command.getInstanceType().toString(), null);
            } else {
                jobs = _asyncMgr.findInstancePendingAsyncJobs(command.getInstanceType().toString(), account.getId());
            }

            if (jobs.size() == 0) {
                return;
            }

            Map<String, AsyncJob> objectJobMap = new HashMap<String, AsyncJob>();
            for (AsyncJob job : jobs) {
                if (job.getInstanceId() == null) {
                    continue;
                }
                String instanceUuid = ApiDBUtils.findJobInstanceUuid(job);
                objectJobMap.put(instanceUuid, job);
            }

            for (ResponseObject response : responses) {
                if (response.getObjectId() != null && objectJobMap.containsKey(response.getObjectId())) {
                    AsyncJob job = objectJobMap.get(response.getObjectId());
                    response.setJobId(job.getUuid());
                    response.setJobStatus(job.getStatus().ordinal());
                }
            }
        }
    }

    private void buildAuditTrail(StringBuffer auditTrailSb, String command, String result) {
        if (result == null) {
            return;
        }
        auditTrailSb.append(" " + HttpServletResponse.SC_OK + " ");
        if (command.equals("createSSHKeyPair")){
            auditTrailSb.append("This result was not logged because it contains sensitive data.");
        } else {
            auditTrailSb.append(StringUtils.cleanString(result));
        }
    }

    @Override
    public boolean verifyRequest(Map<String, Object[]> requestParameters, Long userId) throws ServerApiException {
        try {
            String apiKey = null;
            String secretKey = null;
            String signature = null;
            String unsignedRequest = null;

            String[] command = (String[]) requestParameters.get("command");
            if (command == null) {
                s_logger.info("missing command, ignoring request...");
                return false;
            }

            String commandName = command[0];

            // if userId not null, that mean that user is logged in
            if (userId != null) {
                User user = ApiDBUtils.findUserById(userId);

                try{
                    checkCommandAvailable(user, commandName);
                }
                catch (RequestLimitException ex){
                    s_logger.debug(ex.getMessage());
                    throw new ServerApiException(ApiErrorCode.API_LIMIT_EXCEED, ex.getMessage());
                }
                catch (PermissionDeniedException ex){
                    s_logger.debug("The given command:" + commandName + " does not exist or it is not available for user with id:" + userId);
                    throw new ServerApiException(ApiErrorCode.UNSUPPORTED_ACTION_ERROR, "The given command does not exist or it is not available for user");
                }
                return true;
            } else {
                // check against every available command to see if the command exists or not
                if (!_apiNameCmdClassMap.containsKey(commandName) && !commandName.equals("login") && !commandName.equals("logout")) {
                    s_logger.debug("The given command:" + commandName + " does not exist or it is not available for user with id:" + userId);
                    throw new ServerApiException(ApiErrorCode.UNSUPPORTED_ACTION_ERROR, "The given command does not exist or it is not available for user");
                }
            }

            // - build a request string with sorted params, make sure it's all lowercase
            // - sign the request, verify the signature is the same
            List<String> parameterNames = new ArrayList<String>();

            for (Object paramNameObj : requestParameters.keySet()) {
                parameterNames.add((String) paramNameObj); // put the name in a list that we'll sort later
            }

            Collections.sort(parameterNames);

            String signatureVersion = null;
            String expires = null;

            for (String paramName : parameterNames) {
                // parameters come as name/value pairs in the form String/String[]
                String paramValue = ((String[]) requestParameters.get(paramName))[0];

                if ("signature".equalsIgnoreCase(paramName)) {
                    signature = paramValue;
                } else {
                    if ("apikey".equalsIgnoreCase(paramName)) {
                        apiKey = paramValue;
                    }
                    else if ("signatureversion".equalsIgnoreCase(paramName)) {
                        signatureVersion = paramValue;
                    } else if ("expires".equalsIgnoreCase(paramName)) {
                        expires = paramValue;
                    }

                    if (unsignedRequest == null) {
                        unsignedRequest = paramName + "=" + URLEncoder.encode(paramValue, "UTF-8").replaceAll("\\+", "%20");
                    } else {
                        unsignedRequest = unsignedRequest + "&" + paramName + "=" + URLEncoder.encode(paramValue, "UTF-8").replaceAll("\\+", "%20");
                    }
                }
            }

            // if api/secret key are passed to the parameters
            if ((signature == null) || (apiKey == null)) {
                s_logger.debug("Expired session, missing signature, or missing apiKey -- ignoring request. Signature: " + signature + ", apiKey: " + apiKey);
                return false; // no signature, bad request
            }

            Date expiresTS = null;
            // FIXME: Hard coded signature, why not have an enum
            if ("3".equals(signatureVersion)) {
                // New signature authentication. Check for expire parameter and its validity
                if (expires == null) {
                    s_logger.debug("Missing Expires parameter -- ignoring request. Signature: " + signature + ", apiKey: " + apiKey);
                    return false;
                }
                synchronized (_dateFormat) {
                    try {
                        expiresTS = _dateFormat.parse(expires);
                    } catch (ParseException pe) {
                        s_logger.debug("Incorrect date format for Expires parameter", pe);
                        return false;
                    }
                }
                Date now = new Date(System.currentTimeMillis());
                if (expiresTS.before(now)) {
                    s_logger.debug("Request expired -- ignoring ...sig: " + signature + ", apiKey: " + apiKey);
                    return false;
                }
            }

            TransactionLegacy txn = TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
            txn.close();
            User user = null;
            // verify there is a user with this api key
            Pair<User, Account> userAcctPair = _accountMgr.findUserByApiKey(apiKey);
            if (userAcctPair == null) {
                s_logger.debug("apiKey does not map to a valid user -- ignoring request, apiKey: " + apiKey);
                return false;
            }

            user = userAcctPair.first();
            Account account = userAcctPair.second();

            if (user.getState() != Account.State.enabled || !account.getState().equals(Account.State.enabled)) {
                s_logger.info("disabled or locked user accessing the api, userid = " + user.getId() + "; name = " + user.getUsername() + "; state: " + user.getState() + "; accountState: "
                        + account.getState());
                return false;
            }

            try{
                checkCommandAvailable(user, commandName);
            }
            catch (PermissionDeniedException ex){
                s_logger.debug("The given command:" + commandName + " does not exist or it is not available for user");
                throw new ServerApiException(ApiErrorCode.UNSUPPORTED_ACTION_ERROR, "The given command:" + commandName + " does not exist or it is not available for user with id:" + userId);
            }

            // verify secret key exists
            secretKey = user.getSecretKey();
            if (secretKey == null) {
                s_logger.info("User does not have a secret key associated with the account -- ignoring request, username: " + user.getUsername());
                return false;
            }

            unsignedRequest = unsignedRequest.toLowerCase();

            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(), "HmacSHA1");
            mac.init(keySpec);
            mac.update(unsignedRequest.getBytes());
            byte[] encryptedBytes = mac.doFinal();
            String computedSignature = Base64.encodeBase64String(encryptedBytes);
            boolean equalSig = signature.equals(computedSignature);
            if (!equalSig) {
                s_logger.info("User signature: " + signature + " is not equaled to computed signature: " + computedSignature);
            } else {
                CallContext.register(user, account);
            }
            return equalSig;
        } catch (ServerApiException ex){
            throw ex;
        } catch (Exception ex) {
            s_logger.error("unable to verify request signature");
        }
        return false;
    }

    @Override
    public Long fetchDomainId(String domainUUID) {
        return _domainMgr.getDomain(domainUUID).getId();
    }

    @Override
    public void loginUser(HttpSession session, String username, String password, Long domainId, String domainPath, String loginIpAddress ,Map<String, Object[]> requestParameters) throws CloudAuthenticationException {
        // We will always use domainId first. If that does not exist, we will use domain name. If THAT doesn't exist
        // we will default to ROOT
        if (domainId == null) {
            if (domainPath == null || domainPath.trim().length() == 0) {
                domainId = DomainVO.ROOT_DOMAIN;
            } else {
                Domain domainObj = _domainMgr.findDomainByPath(domainPath);
                if (domainObj != null) {
                    domainId = domainObj.getId();
                } else { // if an unknown path is passed in, fail the login call
                    throw new CloudAuthenticationException("Unable to find the domain from the path " + domainPath);
                }
            }
        }

        UserAccount userAcct = _accountMgr.authenticateUser(username, password, domainId, loginIpAddress, requestParameters);
        if (userAcct != null) {
            String timezone = userAcct.getTimezone();
            float offsetInHrs = 0f;
            if (timezone != null) {
                TimeZone t = TimeZone.getTimeZone(timezone);
                s_logger.info("Current user logged in under " + timezone + " timezone");

                java.util.Date date = new java.util.Date();
                long longDate = date.getTime();
                float offsetInMs = (t.getOffset(longDate));
                offsetInHrs = offsetInMs / (1000 * 60 * 60);
                s_logger.info("Timezone offset from UTC is: " + offsetInHrs);
            }

            Account account = _accountMgr.getAccount(userAcct.getAccountId());

            // set the userId and account object for everyone
            session.setAttribute("userid", userAcct.getId());
            UserVO user = (UserVO) _accountMgr.getActiveUser(userAcct.getId());
            if(user.getUuid() != null){
                session.setAttribute("user_UUID", user.getUuid());
            }

            session.setAttribute("username", userAcct.getUsername());
            session.setAttribute("firstname", userAcct.getFirstname());
            session.setAttribute("lastname", userAcct.getLastname());
            session.setAttribute("accountobj", account);
            session.setAttribute("account", account.getAccountName());

            session.setAttribute("domainid", account.getDomainId());
            DomainVO domain = (DomainVO) _domainMgr.getDomain(account.getDomainId());
            if(domain.getUuid() != null){
                session.setAttribute("domain_UUID", domain.getUuid());
            }

            session.setAttribute("type", Short.valueOf(account.getType()).toString());
            session.setAttribute("registrationtoken", userAcct.getRegistrationToken());
            session.setAttribute("registered", new Boolean(userAcct.isRegistered()).toString());

            if (timezone != null) {
                session.setAttribute("timezone", timezone);
                session.setAttribute("timezoneoffset", Float.valueOf(offsetInHrs).toString());
            }

            // (bug 5483) generate a session key that the user must submit on every request to prevent CSRF, add that
            // to the login response so that session-based authenticators know to send the key back
            SecureRandom sesssionKeyRandom = new SecureRandom();
            byte sessionKeyBytes[] = new byte[20];
            sesssionKeyRandom.nextBytes(sessionKeyBytes);
            String sessionKey = Base64.encodeBase64String(sessionKeyBytes);
            session.setAttribute("sessionkey", sessionKey);

            return;
        }
        throw new CloudAuthenticationException("Failed to authenticate user " + username + " in domain " + domainId + "; please provide valid credentials");
    }

    @Override
    public void logoutUser(long userId) {
        _accountMgr.logoutUser(userId);
        return;
    }

    @Override
    public boolean verifyUser(Long userId) {
        User user = _accountMgr.getUserIncludingRemoved(userId);
        Account account = null;
        if (user != null) {
            account = _accountMgr.getAccount(user.getAccountId());
        }

        if ((user == null) || (user.getRemoved() != null) || !user.getState().equals(Account.State.enabled) || (account == null) || !account.getState().equals(Account.State.enabled)) {
            s_logger.warn("Deleted/Disabled/Locked user with id=" + userId + " attempting to access public API");
            return false;
        }
        return true;
    }


    private void checkCommandAvailable(User user, String commandName) throws PermissionDeniedException {
        if (user == null) {
            throw new PermissionDeniedException("User is null for role based API access check for command" + commandName);
        }

        for (APIChecker apiChecker : _apiAccessCheckers) {
            apiChecker.checkAccess(user, commandName);
        }
    }

    @Override
    public Class<?> getCmdClass(String cmdName) {
        List<Class<?>> cmdList = _apiNameCmdClassMap.get(cmdName);
        if (cmdList == null || cmdList.size() == 0)
            return null;
        else if (cmdList.size() == 1)
            return cmdList.get(0);
        else {
            // determine the cmd class based on calling context
            ResponseView view = ResponseView.Restricted;
            if (CallContext.current() != null
                    && _accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())) {
                view = ResponseView.Full;
            }
            for (Class<?> cmdClass : cmdList) {
                APICommand at = cmdClass.getAnnotation(APICommand.class);
                if (at == null) {
                    throw new CloudRuntimeException(String.format("%s is claimed as a API command, but it doesn't have @APICommand annotation", cmdClass.getName()));
                }
                if (at.responseView() == null) {
                    throw new CloudRuntimeException(String.format(
                            "%s @APICommand annotation should specify responseView attribute to distinguish multiple command classes for a single api name", cmdClass.getName()));
                } else if (at.responseView() == view) {
                    return cmdClass;
                }
            }
            return null;
        }
    }

    // FIXME: rather than isError, we might was to pass in the status code to give more flexibility
    private void writeResponse(HttpResponse resp, final String responseText, final int statusCode, String responseType, String reasonPhrase) {
        try {
            resp.setStatusCode(statusCode);
            resp.setReasonPhrase(reasonPhrase);

            BasicHttpEntity body = new BasicHttpEntity();
            if (BaseCmd.RESPONSE_TYPE_JSON.equalsIgnoreCase(responseType)) {
                // JSON response
                body.setContentType(jsonContentType);
                if (responseText == null) {
                    body.setContent(new ByteArrayInputStream("{ \"error\" : { \"description\" : \"Internal Server Error\" } }".getBytes("UTF-8")));
                }
            } else {
                body.setContentType("text/xml");
                if (responseText == null) {
                    body.setContent(new ByteArrayInputStream("<error>Internal Server Error</error>".getBytes("UTF-8")));
                }
            }

            if (responseText != null) {
                body.setContent(new ByteArrayInputStream(responseText.getBytes("UTF-8")));
            }
            resp.setEntity(body);
        } catch (Exception ex) {
            s_logger.error("error!", ex);
        }
    }

    // FIXME: the following two threads are copied from
    // http://svn.apache.org/repos/asf/httpcomponents/httpcore/trunk/httpcore/src/examples/org/apache/http/examples/ElementalHttpServer.java
    // we have to cite a license if we are using this code directly, so we need to add the appropriate citation or
    // modify the
    // code to be very specific to our needs
    static class ListenerThread extends Thread {
        private HttpService _httpService = null;
        private ServerSocket _serverSocket = null;
        private HttpParams _params = null;

        public ListenerThread(ApiServer requestHandler, int port) {
            try {
                _serverSocket = new ServerSocket(port);
            } catch (IOException ioex) {
                s_logger.error("error initializing api server", ioex);
                return;
            }

            _params = new BasicHttpParams();
            _params.setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 30000).setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
            .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false).setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
            .setParameter(CoreProtocolPNames.ORIGIN_SERVER, "HttpComponents/1.1");

            // Set up the HTTP protocol processor
            BasicHttpProcessor httpproc = new BasicHttpProcessor();
            httpproc.addInterceptor(new ResponseDate());
            httpproc.addInterceptor(new ResponseServer());
            httpproc.addInterceptor(new ResponseContent());
            httpproc.addInterceptor(new ResponseConnControl());

            // Set up request handlers
            HttpRequestHandlerRegistry reqistry = new HttpRequestHandlerRegistry();
            reqistry.register("*", requestHandler);

            // Set up the HTTP service
            _httpService = new HttpService(httpproc, new NoConnectionReuseStrategy(), new DefaultHttpResponseFactory());
            _httpService.setParams(_params);
            _httpService.setHandlerResolver(reqistry);
        }

        @Override
        public void run() {
            s_logger.info("ApiServer listening on port " + _serverSocket.getLocalPort());
            while (!Thread.interrupted()) {
                try {
                    // Set up HTTP connection
                    Socket socket = _serverSocket.accept();
                    DefaultHttpServerConnection conn = new DefaultHttpServerConnection();
                    conn.bind(socket, _params);

                    // Execute a new worker task to handle the request
                    _executor.execute(new WorkerTask(_httpService, conn, _workerCount++));
                } catch (InterruptedIOException ex) {
                    break;
                } catch (IOException e) {
                    s_logger.error("I/O error initializing connection thread", e);
                    break;
                }
            }
        }
    }

    static class WorkerTask extends ManagedContextRunnable {
        private final HttpService _httpService;
        private final HttpServerConnection _conn;

        public WorkerTask(final HttpService httpService, final HttpServerConnection conn, final int count) {
            _httpService = httpService;
            _conn = conn;
        }

        @Override
        protected void runInContext() {
            HttpContext context = new BasicHttpContext(null);
            try {
                while (!Thread.interrupted() && _conn.isOpen()) {
                    _httpService.handleRequest(_conn, context);
                    _conn.close();
                }
            } catch (ConnectionClosedException ex) {
                if (s_logger.isTraceEnabled()) {
                    s_logger.trace("ApiServer:  Client closed connection");
                }
            } catch (IOException ex) {
                if (s_logger.isTraceEnabled()) {
                    s_logger.trace("ApiServer:  IOException - " + ex);
                }
            } catch (HttpException ex) {
                s_logger.warn("ApiServer:  Unrecoverable HTTP protocol violation" + ex);
            } finally {
                try {
                    _conn.shutdown();
                } catch (IOException ignore) {
                }
            }
        }
    }

    @Override
    public String getSerializedApiError(int errorCode, String errorText, Map<String, Object[]> apiCommandParams, String responseType) {
        String responseName = null;
        Class<?> cmdClass = null;
        String responseText = null;

        try {
            if (apiCommandParams == null || apiCommandParams.isEmpty()) {
                responseName = "errorresponse";
            } else {
                Object cmdObj = apiCommandParams.get("command");
                // cmd name can be null when "command" parameter is missing in the request
                if (cmdObj != null) {
                    String cmdName = ((String[]) cmdObj)[0];
                    cmdClass = getCmdClass(cmdName);
                    if (cmdClass != null) {
                        responseName = ((BaseCmd) cmdClass.newInstance()).getCommandName();
                    } else {
                        responseName = "errorresponse";
                    }
                }
            }
            ExceptionResponse apiResponse = new ExceptionResponse();
            apiResponse.setErrorCode(errorCode);
            apiResponse.setErrorText(errorText);
            apiResponse.setResponseName(responseName);
            SerializationContext.current().setUuidTranslation(true);
            responseText = ApiResponseSerializer.toSerializedString(apiResponse, responseType);

        } catch (Exception e) {
            s_logger.error("Exception responding to http request", e);
        }
        return responseText;
    }

    @Override
    public String getSerializedApiError(ServerApiException ex, Map<String, Object[]> apiCommandParams, String responseType) {
        String responseName = null;
        Class<?> cmdClass = null;
        String responseText = null;

        if (ex == null){
            // this call should not be invoked with null exception
            return getSerializedApiError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Some internal error happened", apiCommandParams, responseType);
        }
        try {
            if (ex.getErrorCode() == ApiErrorCode.UNSUPPORTED_ACTION_ERROR || apiCommandParams == null || apiCommandParams.isEmpty()) {
                responseName = "errorresponse";
            } else {
                Object cmdObj = apiCommandParams.get("command");
                // cmd name can be null when "command" parameter is missing in
                // the request
                if (cmdObj != null) {
                    String cmdName = ((String[]) cmdObj)[0];
                    cmdClass = getCmdClass(cmdName);
                    if (cmdClass != null) {
                        responseName = ((BaseCmd) cmdClass.newInstance()).getCommandName();
                    } else {
                        responseName = "errorresponse";
                    }
                }
            }
            ExceptionResponse apiResponse = new ExceptionResponse();
            apiResponse.setErrorCode(ex.getErrorCode().getHttpCode());
            apiResponse.setErrorText(ex.getDescription());
            apiResponse.setResponseName(responseName);
            ArrayList<ExceptionProxyObject> idList = ex.getIdProxyList();
            if (idList != null) {
                for (int i=0; i < idList.size(); i++) {
                    apiResponse.addProxyObject(idList.get(i));
                }
            }
            // Also copy over the cserror code and the function/layer in which
            // it was thrown.
            apiResponse.setCSErrorCode(ex.getCSErrorCode());

            SerializationContext.current().setUuidTranslation(true);
            responseText = ApiResponseSerializer.toSerializedString(apiResponse, responseType);

        } catch (Exception e) {
            s_logger.error("Exception responding to http request", e);
        }
        return responseText;
    }

    public List<PluggableService> getPluggableServices() {
        return _pluggableServices;
    }

    @Inject
    public void setPluggableServices(List<PluggableService> _pluggableServices) {
        this._pluggableServices = _pluggableServices;
    }

    public List<APIChecker> getApiAccessCheckers() {
        return _apiAccessCheckers;
    }

    @Inject
    public void setApiAccessCheckers(List<APIChecker> _apiAccessCheckers) {
        this._apiAccessCheckers = _apiAccessCheckers;
    }
}
