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
package com.redhat.rhn.frontend.action.rhnpackage;

import com.redhat.rhn.common.db.datasource.DataResult;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.manager.rhnpackage.PackageManager;
import com.redhat.rhn.manager.solarispackage.SolarisManager;

/**
 * UpgradableListSetupAction
 * @version $Rev$
 */
public class UpgradableListSetupAction extends BaseSystemPackagesAction {
    @Override
    protected DataResult getDataResult(Server server) {
        if (!server.isSolaris()) {
            return PackageManager.upgradable(server.getId(), null);
        }
        else {
            return SolarisManager.systemUpgradablePackageList(server.getId(), null);
        }        
    }

}
