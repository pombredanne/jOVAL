// Copyright (C) 2012 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.scap.xccdf.handler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.cal10n.LocLogger;

import org.openscap.sce.xccdf.ScriptDataType;
import org.openscap.sce.result.SceResultsType;
import xccdf.schemas.core.CheckContentRefType;
import xccdf.schemas.core.CheckExportType;
import xccdf.schemas.core.CheckImportType;
import xccdf.schemas.core.CheckType;
import xccdf.schemas.core.ObjectFactory;
import xccdf.schemas.core.ResultEnumType;
import xccdf.schemas.core.RuleResultType;
import xccdf.schemas.core.RuleType;
import xccdf.schemas.core.TestResultType;

import org.joval.intf.sce.IProvider;
import org.joval.scap.xccdf.Benchmark;
import org.joval.scap.xccdf.Profile;
import org.joval.scap.xccdf.XccdfException;
import org.joval.scap.xccdf.engine.RuleResult;
import org.joval.scap.xccdf.engine.XPERT;
import org.joval.util.JOVALMsg;

/**
 * XCCDF helper class for SCE processing.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class SCEHandler {
    public static final String NAMESPACE = "http://open-scap.org/page/SCE";

    private static final ObjectFactory FACTORY = new ObjectFactory();

    private Benchmark xccdf;
    private Profile profile;
    private IProvider provider;
    private LocLogger logger;
    private Map<String, Map<String, Script>> scriptTable;

    /**
     * Create an OVAL handler utility for the given XCCDF and Profile.
     */
    public SCEHandler(Benchmark xccdf, Profile profile, IProvider provider, LocLogger logger) {
	this.xccdf = xccdf;
	this.profile = profile;
	this.provider = provider;
	this.logger = logger;
	loadScripts();
    }

    public int ruleCount() {
	return scriptTable.size();
    }

    /**
     * Run all the SCE scripts and integrate the results with the XCCDF results in one step.
     */
    public void integrateResults(TestResultType xccdfResult) {
	//
	// Iterate through the rules and record the results
	//
	for (RuleType rule : profile.getSelectedRules()) {
	    String ruleId = rule.getId();
	    if (scriptTable.containsKey(ruleId)) {
		RuleResultType ruleResult = FACTORY.createRuleResultType();
		ruleResult.setIdref(ruleId);
		ruleResult.setWeight(rule.getWeight());
		if (rule.isSetCheck()) {
		    for (CheckType check : rule.getCheck()) {
			if (NAMESPACE.equals(check.getSystem()) && scriptTable.containsKey(ruleId)) {
			    RuleResult result = new RuleResult();
			    CheckType checkResult = FACTORY.createCheckType();

			    boolean importStdout = false;
			    for (CheckImportType cit : check.getCheckImport()) {
				if ("stdout".equals(cit.getImportName())) {
				    importStdout = true;
				    break;
				}
			    }

			    Map<String, Script> ruleScripts = scriptTable.get(ruleId);
			    if (check.isSetCheckContentRef()) {
				for (CheckContentRefType ref : check.getCheckContentRef()) {
				    checkResult.getCheckContentRef().add(ref);
				    if (ruleScripts.containsKey(ref.getHref())) {
					Script script = ruleScripts.get(ref.getHref());
					try {
					    logger.info("Running SCE script " + ref.getHref());
					    SceResultsType srt = provider.exec(script.getExports(), script.getData());
					    result.add(srt.getResult());
					    if (importStdout) {
						CheckImportType cit = FACTORY.createCheckImportType();
						cit.setImportName("stdout");
						cit.getContent().add(srt.getStdout());
						checkResult.getCheckImport().add(cit);
					    }
					} catch (Exception e) {
					    logger.warn("Error: " + e.getMessage());
					    e.printStackTrace();
					    result.add(ResultEnumType.ERROR);
					}
				    }
				}
			    }
			    if (check.isSetCheckExport()) {
				checkResult.getCheckExport().addAll(check.getCheckExport());
			    }

			    ruleResult.getCheck().add(checkResult);
			    ruleResult.setResult(result.getResult());
			    xccdfResult.getRuleResult().add(ruleResult);
			}
		    }
		}
	    }
	}
    }

    // Private

    /**
     * Create a list of SCE scripts that should be executed based on the profile.
     */
    private void loadScripts() {
	scriptTable = new Hashtable<String, Map<String, Script>>();
	Hashtable<String, String> values = profile.getValues();
	for (RuleType rule : profile.getSelectedRules()) {
	    String ruleId = rule.getId();
	    if (rule.isSetCheck()) {
		for (CheckType check : rule.getCheck()) {
		    if (check.isSetSystem() && check.getSystem().equals(NAMESPACE)) {
			for (CheckContentRefType ref : check.getCheckContentRef()) {
			    if (ref.isSetHref()) {
				String scriptId = ref.getHref();
				try {
				    Map<String, String> exports = new Hashtable<String, String>();
				    for (CheckExportType export : check.getCheckExport()) {
					exports.put(export.getExportName(), values.get(export.getValueId()));
				    }
				    if (!scriptTable.containsKey(ruleId)) {
					scriptTable.put(ruleId, new Hashtable<String, Script>());
				    }
				    scriptTable.get(ruleId).put(scriptId, new Script(scriptId, exports));
				} catch (IllegalArgumentException e) {
				    xccdf.getLogger().warn(e.getMessage());
				} catch (NoSuchElementException e) {
				    String s = JOVALMsg.getMessage(JOVALMsg.ERROR_XCCDF_MISSING_PART, scriptId);
				    xccdf.getLogger().warn(s);
				}
			    }
			}
		    }
		}
	    }
	}
    }

    class Script {
	String id;
	Map<String, String> exports;
	ScriptDataType data;

	Script(String id, Map<String, String> exports) throws NoSuchElementException {
	    this.id = id;
	    data = xccdf.getScript(id);
	    this.exports = exports;
	}

	Map<String, String> getExports() {
	    return exports;
	}

	ScriptDataType getData() {
	    return data;
	}
    }
}
