/**
 * Copyright (c) 2008 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 * 
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation. 
 */
package com.redhat.rhn.manager.kickstart;

import com.redhat.rhn.common.db.datasource.DataResult;
import com.redhat.rhn.common.db.datasource.ModeFactory;
import com.redhat.rhn.common.db.datasource.SelectMode;
import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.common.localization.LocalizationService;
import com.redhat.rhn.common.security.PermissionException;
import com.redhat.rhn.common.validator.ValidatorError;
import com.redhat.rhn.domain.action.Action;
import com.redhat.rhn.domain.action.ActionFactory;
import com.redhat.rhn.domain.action.kickstart.KickstartAction;
import com.redhat.rhn.domain.channel.Channel;
import com.redhat.rhn.domain.channel.ChannelArch;
import com.redhat.rhn.domain.channel.ChannelFactory;
import com.redhat.rhn.domain.channel.ChannelFamily;
import com.redhat.rhn.domain.kickstart.KickstartData;
import com.redhat.rhn.domain.kickstart.KickstartFactory;
import com.redhat.rhn.domain.kickstart.KickstartSession;
import com.redhat.rhn.domain.kickstart.KickstartSessionState;
import com.redhat.rhn.domain.rhnpackage.Package;
import com.redhat.rhn.domain.rhnpackage.PackageFactory;
import com.redhat.rhn.domain.rhnpackage.profile.Profile;
import com.redhat.rhn.domain.rhnpackage.profile.ProfileFactory;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.server.ServerConstants;
import com.redhat.rhn.domain.server.ServerFactory;
import com.redhat.rhn.domain.token.ActivationKey;
import com.redhat.rhn.domain.token.ActivationKeyFactory;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.frontend.dto.PackageListItem;
import com.redhat.rhn.frontend.dto.ServerPath;
import com.redhat.rhn.manager.action.ActionManager;
import com.redhat.rhn.manager.channel.ChannelManager;
import com.redhat.rhn.manager.profile.ProfileManager;
import com.redhat.rhn.manager.rhnpackage.PackageManager;
import com.redhat.rhn.manager.system.BaseSystemOperation;
import com.redhat.rhn.manager.system.SystemManager;
import com.redhat.rhn.manager.token.ActivationKeyManager;

import org.apache.log4j.Logger;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides frequently used data for scheduling a kickstart
 *
 * Throughout this class you'll find references to two different servers:  the host 
 * server and the target server.  The target server is always the system being 
 * kickstarted.  The host server is the system through which the kickstart is being
 * performed; it can be thought of as a type of liason.  The host server receives the
 * kickstart actions, performs the actions on the target server, and represents the 
 * target server in the UI.
 *
 * For most kickstarts, the host server is the same as the target server; that is, the 
 * system being kickstarted is performing the kickstart on itself.  
 *
 * For other types of kickstarts, such as in the case of virtual guests, the system 
 * being kickstarted is different from the system hosting the kickstart.  The virtual
 * host system performs the kickstart on the guest.  When this happens, one of two
 * situations is relevant:
 *
 *    (1) There is no previously-existing target system; the host system must create
 *        it from scratch.  In this scenario, null is passed in as the target system's
 *        ID (simply because it is not known yet).
 *
 *    (2) There is a previously-existing target system and the host must kickstart it.
 *        In this scenario, the target system's ID is not null, but is also not the same
 *        as the host system's ID.
 *
 * Because of the distinction between host and target servers, constructors in this
 * class which accept a single server ID will automatically assume that the system
 * being kickstarted is both the host and the target system.
 *
 * @version $Rev $
 */
public class KickstartScheduleCommand extends BaseSystemOperation {
    
    private static Logger log = Logger.getLogger(KickstartScheduleCommand.class);
    
    // up2date is required to be 2.9.0
    public static final String UP2DATE_VERSION = "2.9.0";
    
    public static final String ACTIVATION_TYPE_KEY = "activationKey";
    public static final String ACTIVATION_TYPE_EXISTING = "existingProfile";
    
    public static final String TARGET_PROFILE_TYPE_EXISTING = "existing";
    public static final String TARGET_PROFILE_TYPE_PACKAGE = "package";
    public static final String TARGET_PROFILE_TYPE_SYSTEM = "system";
    public static final String TARGET_PROFILE_TYPE_NONE = "none";    
    
    
    private User user;
    private KickstartData ksdata;
    private KickstartSession kickstartSession;
    private Date scheduleDate;
    private List packagesToInstall;
    private String activationType;
    private String profileType;    
    private Set activationKeyIds;
    private String proxyHost;
    private Server targetServer;
    
    // Id of the server chosen when we
    // sync to a different server's profile
    private Long serverProfileId;
    // Id of the stored profile chosen
    private Long profileId;
    // Profile created from this KS
    private Profile createdProfile;
    // Static device
    private String staticDevice;
    // extra kernel params set in the UI
    private String kernelParams;
    
    // The server who serves the kickstarts
    private String kickstartServerName;
    // The id of the action scheduled to perform the kickstart
    private Long kickstartActionId;

    /**
     * Constructor for a kickstart where the host and the target are the same system.
     * @param selectedServer server to kickstart
     * @param userIn user performing the kickstart
     */
    public KickstartScheduleCommand(Long selectedServer, User userIn) {
        super(selectedServer);
        initialize(selectedServer, selectedServer, userIn);
    }
    
    /**
     * Constructor for a kickstart where the host and the target are the same system.
     * @param selectedServer server to kickstart
     * @param ksid id of the KickstartData we are using
     * @param userIn user performing the kickstart
     */
    public KickstartScheduleCommand(Long selectedServer, Long ksid, 
            User userIn) {
        this(selectedServer, ksid, userIn, null, null);
    }    
    
    /**
     * Constructor for a kickstart where the host and the target are the same system.  
     * To be used when you want to call the store() method.
     * 
     * @param selectedServer server to kickstart
     * @param ksid id of the KickstartData we are using
     * @param userIn user performing the kickstart
     * @param scheduleDateIn Date to schedule the KS.
     * @param kickstartServerNameIn the name of the server who is serving the kickstart
     */
    public KickstartScheduleCommand(Long selectedServer, 
                                    Long ksid, 
                                    User userIn, 
                                    Date scheduleDateIn, 
                                    String kickstartServerNameIn) {
        this(selectedServer, 
             selectedServer, 
             ksid, 
             userIn, 
             scheduleDateIn, 
             kickstartServerNameIn);
    }

    /**
     * Constructor for a kickstart where the host and the target may or may *not* be
     * the same system.  If the target system does not yet exist, selectedTargetServer
     * should be null.  To be used when you want to call the store() method.
     * 
     * @param selectedHostServer server to host the kickstart
     * @param selectedTargetServer server to be kickstarted
     * @param ksid id of the KickstartData we are using
     * @param userIn user performing the kickstart
     * @param scheduleDateIn Date to schedule the KS.
     * @param kickstartServerNameIn the name of the server who is serving the kickstart
     */
    public KickstartScheduleCommand(Long selectedHostServer, 
                                    Long selectedTargetServer,
                                    Long ksid, 
                                    User userIn, 
                                    Date scheduleDateIn, 
                                    String kickstartServerNameIn) {
        super(selectedHostServer);
        initialize(selectedHostServer, selectedTargetServer, userIn);
        this.setScheduleDate(scheduleDateIn);
        if (ksid != null) {
            this.setKsdata(KickstartFactory.
                lookupKickstartDataByIdAndOrg(userIn.getOrg(), ksid));
            assert (this.getKsdata() != null);
        }
        this.setKickstartServerName(kickstartServerNameIn);
    }

    private void initialize(Long selectedHostServerId, 
                            Long selectedTargetServerId, 
                            User userIn) {

        log.debug("Initializing with selectedHostServerId=" + selectedHostServerId +
                  ", selectedTargetServerId=" + selectedTargetServerId);

        this.setActivationType(ACTIVATION_TYPE_EXISTING);
        this.setPackagesToInstall(new LinkedList());

        // There must always be a host server present.

        Server hServer = 
            ServerFactory.lookupByIdAndOrg(selectedHostServerId, userIn.getOrg());
        assert (hServer != null);
        this.setHostServer(hServer);

        // There may or may not be a target server present.  If so, then look it up in
        // the database.  Otherwise, we'll create the target server later.

        if (selectedTargetServerId != null) {
            this.setTargetServer(ServerFactory.lookupByIdAndOrg(
                    selectedTargetServerId, userIn.getOrg()));
        }

        this.setUser(userIn);
        this.setStaticDevice("");        
    }
    

    /**
     * Looks up a list of applicable kickstart profiles. The list is generated based 
     * on matches between the server's base channel arch and the profile's channel arch
     * @return DataResult, else null if the server does not exist or 
     * does not have a base channel assigned
     */
    public DataResult getKickstartProfiles() {
        log.debug("getKickstartProfiles()");
        DataResult retval = new DataResult(Collections.EMPTY_LIST);

        // Profiles are associated with the host; the target system might not be created
        // yet.  Also, the host will be the one performing the kickstart, so the profile
        // is relative to that system.

        Server hostServer = getHostServer();
        if (hostServer != null) {
            log.debug("getKickstartProfiles(): hostServer isnt null");
            Channel baseChannel = hostServer.getBaseChannel();
            if (baseChannel != null) {
                log.debug("getKickstartProfiles(): hostServer.baseChannel isnt null");
                ChannelArch arch = baseChannel.getChannelArch();
                SelectMode mode = getMode();
                Map params = new HashMap();
                params.put("org_id", this.user.getOrg().getId().toString());
                params.put("prim_arch_id", arch.getId().toString());
                if (arch.getName().equals("x86_64")) {
                    log.debug("    Adding IA-32 to search list.");
                    ChannelArch ia32arch = ChannelFactory.lookupArchByName("IA-32");
                    params.put("sec_arch_id", ia32arch.getId().toString());
                }
                else if (arch.getName().equals("IA-32") && 
                        (hostServer.getServerArch().getName().equals(
                                ServerConstants.getArchI686().getName()) ||
                                hostServer.getServerArch().getName().equals(
                                ServerConstants.getArchATHLON().getName()))) {
                    log.debug("    Adding x86_64 to search list.");
                    ChannelArch x86Arch = ChannelFactory.lookupArchByName("x86_64");
                    params.put("sec_arch_id", x86Arch.getId().toString());
                }
                else {
                    params.put("sec_arch_id", arch.getId().toString());
                }
                retval = mode.execute(params);
            }
        }
        return retval;
    }
    
    protected SelectMode getMode() {
        return ModeFactory.getMode("General_queries", 
                                   "kickstarts_channels_for_org");
    }

    /**
     * 
     * @return list of proxies for org
     */
    public DataResult getProxies() {
        DataResult retval = null;
        SelectMode mode = ModeFactory.getMode("System_queries", 
                "org_proxy_servers");
        Map params = new HashMap();
        params.put("org_id", this.user.getOrg().getId().toString());
        retval = mode.execute(params);
        return retval;
    }
    
    /**
     * 
     * @return primary proxy for server
     */
    public String getPrimaryProxy() {
        String proxyServerName = "";
        DataResult retval = null;
        if (getTargetServer() != null) {
            SelectMode mode = ModeFactory.getMode("System_queries",
                    "proxy_path_for_server");
            Map params = new HashMap();
            params.put("sid", getTargetServer().getId().toString());
            retval = mode.execute(params);
        }
        
        // loop through the proxy path and return 1st in chain
        if (retval != null) {
            for (Iterator itr = retval.iterator(); itr.hasNext();) {
                ServerPath sPath = (ServerPath)itr.next();
                if (sPath.getPosition().toString().equals("1")) {
                    proxyServerName = sPath.getName();
                    break;
                }
            }
        }
        return proxyServerName;
    }

    /**
     * Get the DataResult list of com.redhat.rhn.frontend.dto.ProfileDto that are 
     * compatible with the BaseChannel for the selected KickstartData object.
     *
     * @return DataResult list
     */
    public DataResult getProfiles() {
        DataResult profiles = ProfileManager.compatibleWithChannel(
                this.ksdata.getKsdefault().getKstree().getChannel(),
                user.getOrg(), null);
        return profiles;
    }
 
    /**
     * @return Returns the id of the action scheduled to perform the kickstart.
     */
    public Long getKickstartActionId() {
        return this.kickstartActionId;
    }
 
    /**
     * @return Returns the ksdata.
     */
    public KickstartData getKsdata() {
        return this.ksdata;
    }

    /**
     * @param ksdataIn The ksdata to set.
     */
    public void setKsdata(KickstartData ksdataIn) {
        this.ksdata = ksdataIn;
    }

    /**
     * @return Returns the String representation of the proxy host to use.
     */
    public String getProxyHost() {
        return this.proxyHost;
    }

    /**
     * @param proxyHostIn The proxy host to set.
     */
    public void setProxyHost(String proxyHostIn) {
        this.proxyHost = proxyHostIn;
    }

    /**
     * {@inheritDoc}
     */
    public ValidatorError store() {
        
        ValidatorError e = this.doValidation();
        if (e != null) {
            return e;
        }
        KickstartData data = getKsdata();
        if (data.isRawData()) {
            return storeRawData();
        }
        Server hostServer  = getHostServer();
        log.debug("** Server we are operating on: " + hostServer);

        // Install packages on the host server.

        log.debug("** Creating packageAction");
        Action packageAction = 
            ActionManager.schedulePackageInstall(
                this.user, hostServer, this.packagesToInstall, scheduleDate);
        log.debug("** Created packageAction ? " + packageAction.getId());

        // Make sure we fail all existing sessions for this server since
        // we are scheduling a new one
        
        log.debug("** Cancelling existing sessions.");
        cancelExistingSessions();
        
        kickstartSession = this.setupKickstartSession(packageAction);

        // The host server will contain the tools channel necessary to kickstart the 
        // target system.
        Channel toolsChannel = 
            getToolsChannel(this.ksdata, this.user, getHostServer());
        log.debug("** Looked up tools channel: " + toolsChannel.getName());
        
        
        // If the target system exists already, remove any existing activation keys
        // it might have associated with it.

        log.debug("** ActivationType : Existing profile..");
        if (getTargetServer() != null) {
            ActivationKey oldkey = 
                ActivationKeyFactory.lookupByServer(getTargetServer());
        
            if (oldkey != null) {
                log.debug("** Removing old token");
                ActivationKeyFactory.removeKey(oldkey);
            }
        }
        
        String note = null;
        if (getTargetServer() != null) {
            note = 
                LocalizationService.getInstance().getMessage(
                    "kickstart.session.newtokennote", getTargetServer().getName());
        }
        else {
            // TODO: translate this
            note = "Automatically generated activation key.";
        }

        boolean cfgMgmtFlag = 
            this.getKsdata()
                .getKsdefault()
                .getCfgManagementFlag()
                .booleanValue();

        // Create a new activation key for the target system.

        createKickstartActivationKey(this.user,
                                     this.ksdata, 
                                     getTargetServer(),
                                     this.kickstartSession,
                                     toolsChannel, 
                                     cfgMgmtFlag,
                                     note);
        
        this.createdProfile = processProfileType(this.profileType);
        log.debug("** profile created: " + createdProfile);
        
        // If there is already a target system, determine which channel families it had
        // so that we can assign them to the new target system.
        Map needCf = new HashMap();
        if (getTargetServer() != null) {
            Iterator i = getTargetServer().getChannels().iterator();
            // Map of ChannelFamily IDs->Count values
            // that tracks how many channel entitlements we need
            while (i.hasNext()) {
                Channel c = (Channel) i.next();
                
                ChannelFamily cf = c.getChannelFamily();
                if (needCf.containsKey(cf.getId().toString())) {
                    Integer id = (Integer) 
                        needCf.get(cf.getId().toString());
                    id = new Integer(id.intValue() - 1);
                }
                else {
                    needCf.put(cf.getId().toString(), 
                            new Integer(-1));
                }
            }
        }
        log.debug("** needCF computed: " + needCf);
        
        if (needCf.containsKey(toolsChannel.getChannelFamily().getId().toString())) {
            Integer tools = (Integer) needCf.get(toolsChannel.
                    getChannelFamily().getId().toString());
            tools = new Integer(tools.intValue() + 1);
        }

        log.debug("** NeededChannelFamilies: " + needCf);

        Action kickstartAction =
            (Action) this.scheduleKickstartAction(packageAction);
        ActionFactory.save(packageAction);
        ActionFactory.save(kickstartAction);
        this.kickstartActionId = kickstartAction.getId();
        log.debug("** Created ksaction: " + kickstartAction.getId());
        
        scheduleRebootAction(kickstartAction);
        log.debug("** Done scheduling kickstart session");
        return null;
    }

    private ValidatorError storeRawData() {
        // Make sure we fail all existing sessions for this server since
        // we are scheduling a new one
        
        log.debug("** Cancelling existing sessions.");
        cancelExistingSessions();
        kickstartSession = this.setupKickstartSession(null);
        
        Action kickstartAction =
            (Action) this.scheduleKickstartAction(null);
        ActionFactory.save(kickstartAction);
        this.kickstartActionId = kickstartAction.getId();
        log.debug("** Created ksaction: " + kickstartAction.getId());
        
        scheduleRebootAction(kickstartAction);
        log.debug("** Done scheduling kickstart session");
        return null;
    }

    /**
     * @param firstAction The first Action in the session's action chain
     *
     * return Returns the KickstartSession.
     */
    protected KickstartSession setupKickstartSession(Action firstAction) {
        kickstartSession = new KickstartSession();
        Boolean deployConfig = this.getKsdata().getKsdefault().getCfgManagementFlag();
        
        // TODO: Proxy logic
        
        // Setup the KickstartSession
        kickstartSession.setPackageFetchCount(new Long(0));
        kickstartSession.setDeployConfigs(deployConfig);
        kickstartSession.setAction(firstAction);
        kickstartSession.setKsdata(this.getKsdata());
        kickstartSession.setKstree(this.getKsdata().getTree());
        kickstartSession.setVirtualizationType(this.getKsdata()
                .getKsdefault().getVirtualizationType());
        kickstartSession.setLastAction(new Date());
        kickstartSession.setNewServer(this.getTargetServer());
        kickstartSession.setOldServer(this.getTargetServer());
        kickstartSession.setHostServer(this.getHostServer());
        kickstartSession.setUser(this.getUser());
        kickstartSession.setState(KickstartFactory.SESSION_STATE_CREATED);
        kickstartSession.setOrg(this.getUser().getOrg());
        kickstartSession.setSystemRhnHost(this.getProxyHost());
        kickstartSession
            .setVirtualizationType(this.getKsdata().getKsdefault().getVirtualizationType());
        log.debug("** Saving new KickstartSession: " + kickstartSession.getId());
        KickstartFactory.saveKickstartSession(kickstartSession);
        log.debug("** Saved new KickstartSession: " + kickstartSession.getId());

        return kickstartSession;
    }

    /**
     * @param prereqAction the prerequisite for this action
     *
     * @return Returns the KickstartAction
     */
    public Action scheduleKickstartAction(Action prereqAction) {

        // We will schedule the kickstart action against the host server, since the host
        // server is the liason for the target server.

        KickstartAction ksAction =
            (KickstartAction) 
                ActionManager.scheduleKickstartAction(this.getKsdata(),
                                                      this.getUser(), 
                                                      this.getHostServer(),
                                                      this.getScheduleDate(),
                                                      this.getExtraOptions());

        if (prereqAction != null) {
            ksAction.setPrerequisite(prereqAction.getId());
        }
        ksAction.getKickstartActionDetails().setStaticDevice(this.getStaticDevice());
        return (Action) ksAction;
    }

    /**
     * @param prereqAction the prerequisite for this action
     *
     * @return Returns the rebootAction (if any)
     */
    public Action scheduleRebootAction(Action prereqAction) {
    
        // All actions must be scheduled against the host server.

        Action rebootAction = ActionManager.scheduleRebootAction(this.getUser(), 
                this.getHostServer(), this.getScheduleDate());
        log.debug("** Created rebootAction");
        rebootAction.setPrerequisite(prereqAction.getId());
        rebootAction.setEarliestAction(this.getScheduleDate());
        rebootAction.setOrg(this.getUser().getOrg());
        rebootAction.setName(rebootAction.getActionType().getName());
        log.debug("** saving reboot action: " + (rebootAction == null));
        ActionFactory.save(rebootAction);
        log.debug("** Saved rebootAction: " + rebootAction.getId());

        return rebootAction;
    }

    /**
     * Do the validation needed for this command.  This ensures that the system
     * hosting the kickstart has the necessary resources to do so.
     *
     * @return Returns a ValidatorError, if any errors occur
     */
    public ValidatorError doValidation() {
        KickstartData data = getKsdata();
        if (!data.isRawData()) {
            Server hostServer = getHostServer();

            // Check base channel.
            log.debug("** Checking basechannel.");
            if (hostServer.getBaseChannel() == null) {
                return new ValidatorError("kickstart.schedule.nobasechannel", 
                        hostServer.getName());
            }
            
            // Check that we have a valid ks package
            log.debug("** Checking validkspackage");
            ValidatorError error = validateKickstartPackage(); 
            if (error != null) {
                return error;
            }
            
            // Check that we have a valid up2date version
            log.debug("** Checking valid up2date");
            error = validateUp2dateVersion();
            if (error != null) {
                return error;
            }

            // Check that we have a tools channel.  The host server needs to contain the
            // tools channel since it is the one performing the actions.

            log.debug("** Checking for a Spacewalk tools channel");
            Channel toolsChannel = getToolsChannel(this.ksdata, this.user, hostServer);
            if (toolsChannel == null) {
                Object[] args = new Object[2];
                args[0] = this.getKsdata().getChannel().getId();
                args[1] = this.getKsdata().getChannel().getName();
                return new ValidatorError("kickstart.session.notoolschannel",
                                          args);
            }
        }
        return null;
    }
    
    /**
     * Create a one time activation key for use with a kickstart
     * @param creator of the key
     * @param ksdata associated with the key
     * @param server being kickstarted (can be null)
     * @param session associated with the kickstart (NOT NULL)
     * @param toolsChannel containing up2date and autokickstart rpms
     * @param deployConfigs if you want to or not
     * @param note to add to key
     * @return ActivationKey that has been saved to the DB.
     */
    public static ActivationKey createKickstartActivationKey(User creator, 
            KickstartData ksdata,
            Server server,
            KickstartSession session, 
            Channel toolsChannel,             
            boolean deployConfigs, 
            String note) {
        
        // Now create ActivationKey
        ActivationKey key = ActivationKeyManager.getInstance().
                                createNewReActivationKey(creator, server, note, session);
        key.setDeployConfigs(deployConfigs);
        key.setUsageLimit(new Long(1));
        key.addEntitlement(ServerConstants.getServerGroupTypeProvisioningEntitled());
        ActivationKeyFactory.save(key);
        
        // We are swapping base channels.  In this case 
        // we only add the ksdata's base channel and the tools
        // channel to the key.
        if (server == null || (!server.getBaseChannel().getId().equals(
                ksdata.getTree().getChannel().getId()))) {
            log.debug("** We are switching base channels or server is null.  " +
                    "Use base channel from Kickstart Tree: " + 
                    ksdata.getTree().getChannel().getLabel());
            log.debug("*** Channel ID: " + ksdata.getTree().getChannel().getId());
            key.addChannel(ksdata.getTree().getChannel());
        }
        else {
            log.debug("** We are keeping same base channel.  keeping channels");
            // Add the System's channels to the key
            Iterator i = server.getChannels().iterator();
            while (i.hasNext()) {
                key.addChannel((Channel) i.next());
            }
        }
        // Add child channels to the key
        if (ksdata.getChildChannels() != null && ksdata.getChildChannels().size() > 0) {
            Iterator i = ksdata.getChildChannels().iterator();
            log.debug("Add the child Channels");
            while (i.hasNext()) {
               key.addChannel((Channel) i.next());
            }
        }

        //Only add the toolsChannel to the activation key if it exists.
        //This can happen on a satellite that has synced a base channel
        // but not the tools child channel, or when the kickstart channel
        // is a custom channel.  See bug #201561
        if (toolsChannel != null) {
            key.addChannel(toolsChannel);
        }
        if (toolsChannel != null) {
            key.addChannel(toolsChannel);
        }
        log.debug("** Saving new token");
        ActivationKeyFactory.save(key);
        log.debug("** Saved new token: " + key.getId());
        return key;
    }
    
    
    /**
     * Create ExtraOptions string
     * @return extraOptions that will be appended to the Kickstart.
     */
    public String getExtraOptions() {
        StringBuffer retval = new StringBuffer();
        retval.append("ks=");
        if (this.proxyHost != null) {
            retval.append(kickstartSession.getUrl(this.proxyHost, 
                    this.scheduleDate));
        }
        else {
            retval.append(kickstartSession.getUrl(this.kickstartServerName, 
                    this.scheduleDate));
        }

        String extraParams = new String(" ksdevice=eth0");
        /** Some examples:
        dhcp:eth0 , dhcp:eth2, static:10.1.4.75
        static:146.108.30.184, static:auto, static:eth0
         */
        if (this.getKsdata().getStaticDevice() != null) {
            staticDevice = this.getKsdata().getStaticDevice();
            if (staticDevice.indexOf("dhcp:") >= 0) {
                // Get rid of the dhcp:
                extraParams = " ksdevice=" + staticDevice.substring(
                        staticDevice.indexOf("dhcp:") + "dhcp:".length());
                log.debug("extraParams: " + extraParams);
                staticDevice = "";
            }
            else if (staticDevice.indexOf("static:") >= 0) {
                // Get rid of the static:
                staticDevice = staticDevice.substring(
                        staticDevice.indexOf("static:") + "static:".length());
                extraParams = "";
            }
        }
        if (this.kernelParams != null) {
            extraParams = extraParams + " " + this.kernelParams;
        }
        if (this.getKsdata().getKernelParams() != null) {
            extraParams = extraParams + " " + this.getKsdata().getKernelParams(); 
        }
        retval.append(extraParams);
        
        log.debug("extraParams: " + extraParams);
        log.debug("staticDevice: " + staticDevice);
        log.debug("retval: " + retval);
        
        return retval.toString();
    }

    private Profile processProfileType(String profileTypeIn) {
        log.debug("PROFILE_TYPE=" + profileTypeIn);
        
        if (profileTypeIn == null || 
            profileTypeIn.length() == 0 || // TODO: fix this hack
                profileTypeIn.equals(TARGET_PROFILE_TYPE_NONE)) {
            return null;
        }
        Profile retval = null;
        // Profile of this existing system's packages
        String pname = LocalizationService.getInstance().
            getMessage("kickstart.session.newprofile", 
                this.kickstartSession.getId().toString()); 
        if (profileTypeIn.equals(TARGET_PROFILE_TYPE_EXISTING)) {
            log.debug("    TARGET_PROFILE_TYPE_EXISTING");
            // "Profile for kickstart session "
            retval = ProfileManager.createProfile(
                    ProfileFactory.TYPE_SYNC_PROFILE, this.user, 
                        getTargetServer().getBaseChannel(), pname, pname);
            ProfileManager.copyFrom(this.server, retval);
        }
        // Profile of 'stored profile'
        else if (profileTypeIn.equals(TARGET_PROFILE_TYPE_PACKAGE)) {
            log.debug("    TARGET_PROFILE_TYPE_PACKAGE");
            if (this.profileId == null) {
                throw new UnsupportedOperationException(
                        "You specified a target profile type" + 
                        TARGET_PROFILE_TYPE_PACKAGE + 
                        " but this.profileId is null");
            }
            retval = ProfileManager.
                lookupByIdAndOrg(this.profileId, this.user.getOrg());
        }
        // Some other system's profile
        else if (profileTypeIn.equals(TARGET_PROFILE_TYPE_SYSTEM)) {
            Server otherServer = ServerFactory.lookupById(this.serverProfileId);
            log.debug("    TARGET_PROFILE_TYPE_SYSTEM");
            log.debug("    this.serverProfileId      : " + this.serverProfileId);
            log.debug("    otherServer               : " + otherServer);
            if (otherServer != null) {
                log.debug("otherServer.Id            : " + otherServer.getId());
                log.debug("otherServer.getBaseChannel: " + otherServer.getBaseChannel());
            }
            
            retval = ProfileManager.createProfile(
                    ProfileFactory.TYPE_SYNC_PROFILE, this.user, 
                        otherServer.getBaseChannel(), pname, pname);
        }
        this.kickstartSession.setServerProfile(retval);
        KickstartFactory.saveKickstartSession(this.kickstartSession);
        
        if (getTargetServer() != null) {
            HibernateFactory.getSession().refresh(getTargetServer());
        }
        
        
        // TODO: Compute missing packages and forward user to the missing page
        
        return retval;
    }

    /**
     * Get the tools Channel for a KickstartData object and server
     * @param ksdata to fetch tools channel for
     * @param user who is looking up the channel
     * @param server to check against
     * @return Channel if found
     */
    public static Channel getToolsChannel(KickstartData ksdata, User user, Server server) {
        
        if (server != null && ksdata.getChannel().getId().
                equals(server.getBaseChannel().getId())) {
            log.debug("  ** getToolsChannel() returning ksdata's channel");
            return ksdata.getChannel();
        }
        else {
            log.debug("  ** getToolsChannel() looking for tools channel as a child");
            Channel kschannel = ksdata.getChannel();
            Channel toolsChannel = ChannelManager.getToolsChannel(kschannel, user);
            if (toolsChannel != null) {
                return toolsChannel;
            }
        }
        log.error("Tools channel not found!  " +
                "This means we can't find the rhn-kickstart package.");
        return null;
        
    }

    /**
     * Cancel existing kickstart sessions on the host server for the system to be 
     * kickstarted (the target server).
     */
    private void cancelExistingSessions() {
        Server hostServer = getHostServer();

        List sessions = KickstartFactory.
            lookupAllKickstartSessionsByServer(hostServer.getId());
        if (sessions != null) {
            log.debug("    Found sessions: " + sessions);
            Iterator i = sessions.iterator();
            while (i.hasNext()) {
                KickstartSession sess = (KickstartSession) i.next();
                if (sess != null && 
                        sess.getState() != null) {
                    log.debug("    Working with session: " + 
                            sess.getState().getLabel() + " id: " + sess.getId());
                }
                KickstartSessionState state = sess.getState();
                
                if (!state.equals(KickstartFactory.SESSION_STATE_FAILED) ||
                        !state.equals(KickstartFactory.SESSION_STATE_COMPLETE)) {
                    log.debug("    need to cancel this Session this.s: " + 
                            hostServer.getId() + " sess.hostServer: " + 
                            (sess.getHostServer() == null ? 
                                 "null" :
                                 "" + sess.getHostServer().getId()));
                    if (sess.getHostServer() != null &&
                            sess.getHostServer().getId().equals(hostServer.getId())) {
                        log.debug("    Marking session failed.");
                        sess.markFailed(
                                LocalizationService.getInstance().
                                    getMessage("kickstart.session.newsession"));
                    }
                }
            }
        }
         
    }

    /**
     * Get the id of the Package installed for this KS.  Here we'll verify that the
     * kickstart package exists in the host server's tools channel.  The host server will
     * need it to perform necessary actions on either itself or the target system (if
     * different).
     * @return Long id of Package used for this KS.
     */
    public ValidatorError validateKickstartPackage() {
        
        Server hostServer = getHostServer();

        Iterator i = SystemManager.subscribableChannels(hostServer.getId(), 
                this.user.getId(), hostServer.getBaseChannel().getId()).iterator();
        
        Set channelIds = new HashSet();
        // Add list of subscribable channels
        while (i.hasNext()) {
            Map row = (Map) i.next();
            channelIds.add(row.get("id"));
        }
        // Add list of channels
        Set serverChannelIds = new HashSet();
        i = hostServer.getChannels().iterator();
        while (i.hasNext()) {
            Channel c = (Channel) i.next();
            serverChannelIds.add(c.getId());
        }
        
        channelIds.addAll(serverChannelIds);
        i = channelIds.iterator();
        while (i.hasNext()) {
            Object id = i.next();
            Long cid = (Long) id; 
            log.debug("    Checking on:" + cid + " for: " + 
                    getKickstartPackageName());
            List result = ChannelManager.listLatestPackagesEqual(cid, 
                    getKickstartPackageName());
            log.debug("    size: " + result.size());
            
            if (result.size() > 0) {
                Map row = (Map) result.get(0);
                log.debug("    Found the package: " + row);
                Map pkgToInstall = new HashMap();
                pkgToInstall.put("name_id", (Long) row.get("name_id"));
                pkgToInstall.put("evr_id", (Long) row.get("evr_id"));
                this.packagesToInstall.add(pkgToInstall);
                log.debug("    packagesToInstall: " + packagesToInstall);
                
                // this.kickstartPackageId = ;
                if (!serverChannelIds.contains(cid)) {
                    log.debug("    Subscribing to: " + cid);
                    Channel c = ChannelFactory.lookupById(cid);
                    try {
                        SystemManager.subscribeServerToChannel(this.user, hostServer, c);
                        log.debug("    Subscribed: " + cid);
                    }                     
                    catch (PermissionException pe) {
                        return new ValidatorError("kickstart.schedule.cantsubscribe");
                    }
                    catch (Exception e) {
                        return new ValidatorError(
                                "kickstart.schedule.cantsubscribe.channel", c.getName());
                    }
                }
                return null; 
            }
        }
        
        return new ValidatorError("kickstart.schedule.nopackage", 
                this.getKsdata().getChannel().getName());
    }
    
    /**
     * Return the kickstart package name for this kickstart action.
     * @return kickstart package name
     */
    public String getKickstartPackageName() {
        return this.ksdata.getKickstartPackageName();
    }
    
    // Check to make sure up2date is 2.9.0
    protected ValidatorError validateUp2dateVersion() {
        Server hostServer = getHostServer();
        List packages = PackageManager.systemPackageList(hostServer.getId(), null);
        if (packages != null) {
            log.debug("    packages.size() : " + packages.size());
        }
        // PackageListItem
        Iterator i = packages.iterator();
        String up2dateepoch = null;
        String up2dateversion = null;
        String up2daterelease = null;
        
        while (i.hasNext()) {
            PackageListItem pli = (PackageListItem) i.next();
            if (pli.getName().equals("yum-rhn-plugin")) {
                // found yum-rhn-plugin - returning
                return null;
            }

            if (pli.getName().equals("up2date")) {
                log.debug("    found up2date ...");
                up2dateepoch = pli.getEpoch();
                up2dateversion = pli.getVersion();
                up2daterelease = pli.getRelease();
                
                log.debug("    e: " + up2dateepoch + " v: " + up2dateversion + 
                        " r : " + up2daterelease);
            }
        }
        
        if (up2dateepoch == null && up2dateversion == null && 
                up2daterelease == null) {
            Object[] args = new Object[2];
            args[0] = hostServer.getId();
            args[1] = hostServer.getName();
            return new ValidatorError("kickstart.schedule.noup2date", args);
        }
        
        
        up2dateepoch = up2dateepoch == null ? "0" : up2dateepoch;
        up2dateversion = up2dateversion == null ? "0" : up2dateversion;
        up2daterelease = up2daterelease == null ? "0" : up2daterelease;
        
        int comp = PackageManager.verCmp(up2dateepoch, 
                up2dateversion, 
                up2daterelease,
                "0",
                UP2DATE_VERSION, 
                "0");
        log.debug("    Got back comp from verCmp: " + comp);
        if (comp < 0) {
            Long packageId = PackageManager.
                getServerNeededUpdatePackageByName(hostServer.getId(), "up2date");
            if (packageId == null) {
                Object[] args = new Object[2];
                args[0] = UP2DATE_VERSION;
                args[1] = up2dateversion;
                return new ValidatorError("kickstart.schedule.noup2dateinchannel", args);
            }
            Package p = PackageFactory.lookupByIdAndUser(packageId, this.user);
            
            Map evrmap = new HashMap();
            evrmap.put("name_id", p.getPackageName().getId());
            evrmap.put("evr_id", p.getPackageEvr().getId());
            packagesToInstall.add(evrmap);
        }
        else { 
            return null;
        }
        
        return null;
    }
    

    
    /**
     * Get the list of possible Activation keys to chose from
     * @return DataResult of 
     */
    public DataResult getActivationKeys() {
        return KickstartLister.getInstance().
            getActivationKeysInOrg(this.user.getOrg(), null);
    }

    /**
     * Get the list of compatible systems you could sync to
     * @return DataResult of System DTOs
     */
    public DataResult getCompatibleSystems() {
        DataResult dr = SystemManager.systemsSubscribedToChannel(
                this.getKsdata().getKsdefault().getKstree().getChannel(), user);
        return dr;
    }

    
    /**
     * @return Returns the packagesToInstall.
     */
    public List getPackagesToInstall() {
        return packagesToInstall;
    }

    
    /**
     * @param packagesToInstallIn The packagesToInstall to set.
     */
    public void setPackagesToInstall(List packagesToInstallIn) {
        this.packagesToInstall = packagesToInstallIn;
    }

    
    /**
     * @return Returns the activationType.
     */
    public String getActivationType() {
        return activationType;
    }

    
    /**
     * @param activationTypeIn The activationType to set.
     */
    public void setActivationType(String activationTypeIn) {
        this.activationType = activationTypeIn;
    }

    
    /**
     * @return Returns the profileType.
     */
    public String getProfileType() {
        return profileType;
    }

    
    /**
     * @param profileTypeIn The profileType to set.
     */
    public void setProfileType(String profileTypeIn) {
        this.profileType = profileTypeIn;
    }        
    
    /**
     * @return Returns the activationKeyIds.
     */
    public Set getActivationKeyIds() {
        return activationKeyIds;
    }

    
    /**
     * @param activationKeyIdsIn The activationKeyIds to set.
     */
    public void setActivationKeyIds(Set activationKeyIdsIn) {
        this.activationKeyIds = activationKeyIdsIn;
    }

    
    /**
     * @return Returns the kickstartSession.
     */
    public KickstartSession getKickstartSession() {
        return kickstartSession;
    }

    
    /**
     * @param kickstartSessionIn The kickstartSession to set.
     */
    public void setKickstartSession(KickstartSession kickstartSessionIn) {
        this.kickstartSession = kickstartSessionIn;
    }

    
    /**
     * @return Returns the profileId.
     */
    public Long getProfileId() {
        return profileId;
    }

    
    /**
     * @param profileIdIn The profileId to set.
     */
    public void setProfileId(Long profileIdIn) {
        this.profileId = profileIdIn;
    }

    
    /**
     * @return Returns the serverProfileId.
     */
    public Long getServerProfileId() {
        return serverProfileId;
    }

    
    /**
     * @param serverProfileIdIn The serverProfileId to set.
     */
    public void setServerProfileId(Long serverProfileIdIn) {
        this.serverProfileId = serverProfileIdIn;
    }

    
    /**
     * @return Returns the createdProfile.
     */
    public Profile getCreatedProfile() {
        return createdProfile;
    }

    
    /**
     * @return Returns the kernelParams.
     */
    public String getKernelParams() {
        return kernelParams;
    }

    
    /**
     * @param kernelParamsIn The kernelParams to set.
     */
    public void setKernelParams(String kernelParamsIn) {
        this.kernelParams = kernelParamsIn;
    }

    /**
     * Set the activationkey id to use
     * @param idIn to add to the Kickstart
     */
    public void setActivationKeyId(Long idIn) {
        if (this.activationKeyIds == null) {
            this.activationKeyIds = new HashSet();
        }
        this.activationKeyIds.add(idIn);
        
    }
    
    /**
     * 
     * @param serverIn Proxy Host to set for this ks session
     */
    public void setProxy(Server serverIn) {
        if (serverIn != null) {
            this.proxyHost = serverIn.getHostname();
        }
    }

    /**
     * @return Returns the scheduleDate.
     */
    public Date getScheduleDate() {
        return scheduleDate;
    }

    
    /**
     * @param scheduleDateIn The scheduleDate to set.
     */
    public void setScheduleDate(Date scheduleDateIn) {
        this.scheduleDate = scheduleDateIn;
    }

    /**
     * @return Returns the kickstart server name.
     */
    public String getKickstartServerName() {
        return kickstartServerName;
    }

    
    /**
     * @param kickstartServerNameIn The kickstartServerName to set.
     */
    public void setKickstartServerName(String kickstartServerNameIn) {
        this.kickstartServerName = kickstartServerNameIn;
    }

    /**
     * @return Returns the staticDevice.
     */
    public String getStaticDevice() {
        return staticDevice;
    }

    
    /**
     * @param staticDeviceIn The staticDevice to set.
     */
    public void setStaticDevice(String staticDeviceIn) {
        this.staticDevice = staticDeviceIn;
    }

    /**
     * @return Returns the user.
     */
    public User getUser() {
        return user;
    }

    
    /**
     * @param userIn The user to set.
     */
    public void setUser(User userIn) {
        this.user = userIn;
    }

    /**
     * @return The host server.
     */
    public Server getHostServer() {
        return this.server;
    }

    /**
     * @param serverIn The host server to set.
     */
    public void setHostServer(Server serverIn) {
        this.server = serverIn;
    }

    /**
     * @return The target server.
     */
    public Server getTargetServer() {
        return this.targetServer;
    }

    /**
     * @param serverIn The server to set.
     */
    public void setTargetServer(Server serverIn) {
        this.targetServer = serverIn;
    }
}
