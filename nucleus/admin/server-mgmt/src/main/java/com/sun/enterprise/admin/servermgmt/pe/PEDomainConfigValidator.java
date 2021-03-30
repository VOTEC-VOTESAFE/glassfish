/*
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.enterprise.admin.servermgmt.pe;

import com.sun.enterprise.admin.servermgmt.DomainConfig;
import com.sun.enterprise.admin.servermgmt.DomainConfigValidator;
import com.sun.enterprise.admin.servermgmt.FileValidator;
import com.sun.enterprise.admin.servermgmt.InvalidConfigException;
import com.sun.enterprise.admin.servermgmt.PortValidator;
import com.sun.enterprise.admin.servermgmt.StringValidator;
import com.sun.enterprise.util.i18n.StringManager;
import java.lang.StringBuffer;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * This class defines the domain config entries that are required to create a PE Tomcat domain.
 */
public class PEDomainConfigValidator extends DomainConfigValidator {
    /**
     * i18n strings manager object
     */
    private static final StringManager strMgr = StringManager.getManager(PEDomainConfigValidator.class);

    private static final String lInstallRoot = strMgr.getString("installRoot");
    private static final String lDomainsRoot = strMgr.getString("domainsRoot");
    private static final String lJavaHome = strMgr.getString("javaHome");
    private static final String lAdminPort = strMgr.getString("adminPort");
    private static final String lInstancePort = strMgr.getString("instancePort");
    private static final String lHostName = strMgr.getString("hostName");
    private static final String lJmsPort = strMgr.getString("jmsPort");
    private static final String lOrbPort = strMgr.getString("orbPort");

    static DomainConfigEntryInfo[] entries = new DomainConfigEntryInfo[] {
            new DomainConfigEntryInfo(DomainConfig.K_INSTALL_ROOT, "java.lang.String", new FileValidator(lInstallRoot, "dr")),
            new DomainConfigEntryInfo(DomainConfig.K_DOMAINS_ROOT, "java.lang.String", new FileValidator(lDomainsRoot, "drw")),
            new DomainConfigEntryInfo(DomainConfig.K_ADMIN_PORT, "java.lang.Integer", new PortValidator(lAdminPort)),
            new DomainConfigEntryInfo(DomainConfig.K_INSTANCE_PORT, "java.lang.Integer", new PortValidator(lInstancePort)),
            new DomainConfigEntryInfo(DomainConfig.K_HOST_NAME, "java.lang.String", new StringValidator(lHostName)),
            new DomainConfigEntryInfo(DomainConfig.K_ORB_LISTENER_PORT, "java.lang.Integer", new PortValidator(lOrbPort)),
            new DomainConfigEntryInfo(DomainConfig.K_JMS_PORT, "java.lang.Integer", new PortValidator(lJmsPort)) };

    /** Creates a new instance of PEDomainConfigValidator */
    public PEDomainConfigValidator() {
        super(entries);
    }

    public void validate(Object domainConfig) throws InvalidConfigException {
        super.validate(domainConfig);
        uniquePorts((DomainConfig) domainConfig);
    }

    protected boolean isValidate(String name, Object domainConfig) {
        boolean isPortEntry = DomainConfig.K_ADMIN_PORT.equals(name) || DomainConfig.K_INSTANCE_PORT.equals(name)
                || DomainConfig.K_ORB_LISTENER_PORT.equals(name) || DomainConfig.K_JMS_PORT.equals(name);
        return (isPortEntry) ? isValidatePorts((Map) domainConfig) : true;
    }

    private boolean isValidatePorts(Map domainConfig) {
        Boolean isValidatePorts = (Boolean) domainConfig.get(DomainConfig.K_VALIDATE_PORTS);
        return (null != isValidatePorts) ? isValidatePorts.booleanValue() : true;
    }

    final void uniquePorts(final DomainConfig dc) throws InvalidConfigException {
        final Map ports = dc.getPorts();
        final Set portValues = new HashSet(ports.values());
        if (ports.keySet().size() != portValues.size()) {
            throw new InvalidConfigException(getMessage(ports));
        }
    }

    private final String getMessage(final Map ports) {
        return getLocalizedString("duplicatePorts", getDuplicatePorts(ports));
    }

    private final String getLocalizedString(final String key, final Object o) {
        return strMgr.getString(key, o);
    }

    final String getDuplicatePorts(final Map ports) {
        return printDuplicatesFromMap(reverseMap(ports));
    }

    /**
     * Reverse the given map. i.e. a keys in the input map are values in the output map, and values in the input map are
     * kays in the output map. Note that the values in the output map must be sets, to allow for many to 1 relations in the
     * input map.
     */
    private final Map reverseMap(final Map inputMap) {
        final Map outputMap = new TreeMap();
        for (Iterator entries = inputMap.entrySet().iterator(); entries.hasNext();) {
            Map.Entry entry = (Map.Entry) entries.next();
            addEntryToMap(entry.getKey(), entry.getValue(), outputMap);
        }
        return outputMap;
    }

    /**
     * Add the given key/value pair, but reversing it, in the given map. reversal means that the values in the map must be
     * sets.
     */
    private final void addEntryToMap(final Object key, final Object value, final Map map) {
        if (!map.containsKey(value)) {
            map.put(value, new TreeSet());
        }
        ((Set) map.get(value)).add(key);
    }

    /**
     * Return a string representation of the given map, but only for those entries where the value has a size greater than 1
     * 
     * @param map a map of key to Set of value
     */
    private final String printDuplicatesFromMap(final Map map) {
        final StringBuffer sb = new StringBuffer();
        final Iterator it = map.entrySet().iterator();
        Map.Entry entry = getNextDuplicate(it);
        if (entry != null) {
            printEntry(sb, entry);
            while ((entry = getNextDuplicate(it)) != null) {
                sb.append(", ");
                printEntry(sb, entry);
            }
        }
        return sb.toString();
    }

    /**
     * Get next entry from iterator whose value is a set of cardinality greater than 1
     */
    private final Map.Entry getNextDuplicate(final Iterator it) {
        while (it.hasNext()) {
            Map.Entry result = (Map.Entry) it.next();
            if (((Set) result.getValue()).size() > 1) {
                return result;
            }
        }
        return null;
    }

    private final void printEntry(final StringBuffer sb, final Map.Entry entry) {
        printEntry(sb, (Object) entry.getKey(), (Set) entry.getValue());
    }

    /**
     * Print the given key and set onto the given string buffer. Note that the set is considered to contain more than one
     * object
     */
    private final void printEntry(final StringBuffer sb, final Object key, final Set dups) {
        sb.append(key).append(" -> ");
        printSet(sb, dups);
    }

    /**
     * Print the given set on the given string buffer
     */
    private final void printSet(final StringBuffer sb, final Set set) {
        sb.append("{");
        String separator = "";
        for (Iterator it = set.iterator(); it.hasNext();) {
            sb.append(separator).append(it.next());
            separator = ", ";
        }
        sb.append("}");
    }
}
