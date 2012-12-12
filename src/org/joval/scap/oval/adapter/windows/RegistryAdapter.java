// Copyright (C) 2011 jOVAL.org.  All rights reserved.
// This software is licensed under the AGPL 3.0 license available at http://www.joval.org/agpl_v3.txt

package org.joval.scap.oval.adapter.windows;

import java.io.InputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import oval.schemas.common.MessageLevelEnumeration;
import oval.schemas.common.MessageType;
import oval.schemas.common.OperationEnumeration;
import oval.schemas.common.SimpleDatatypeEnumeration;
import oval.schemas.definitions.core.ObjectType;
import oval.schemas.definitions.windows.RegistryBehaviors;
import oval.schemas.definitions.windows.RegistryObject;
import oval.schemas.systemcharacteristics.core.EntityItemAnySimpleType;
import oval.schemas.systemcharacteristics.core.EntityItemIntType;
import oval.schemas.systemcharacteristics.core.EntityItemStringType;
import oval.schemas.systemcharacteristics.core.FlagEnumeration;
import oval.schemas.systemcharacteristics.core.ItemType;
import oval.schemas.systemcharacteristics.core.StatusEnumeration;
import oval.schemas.systemcharacteristics.windows.EntityItemRegistryHiveType;
import oval.schemas.systemcharacteristics.windows.EntityItemRegistryTypeType;
import oval.schemas.systemcharacteristics.windows.EntityItemWindowsViewType;
import oval.schemas.systemcharacteristics.windows.RegistryItem;
import oval.schemas.results.core.ResultEnumeration;

import org.joval.intf.plugin.IAdapter;
import org.joval.intf.system.IBaseSession;
import org.joval.intf.util.ISearchable;
import org.joval.intf.windows.registry.IBinaryValue;
import org.joval.intf.windows.registry.IDwordValue;
import org.joval.intf.windows.registry.IExpandStringValue;
import org.joval.intf.windows.registry.IKey;
import org.joval.intf.windows.registry.IMultiStringValue;
import org.joval.intf.windows.registry.IQwordValue;
import org.joval.intf.windows.registry.IStringValue;
import org.joval.intf.windows.registry.IValue;
import org.joval.intf.windows.system.IWindowsSession;
import org.joval.io.LittleEndian;
import org.joval.scap.oval.CollectException;
import org.joval.scap.oval.Factories;
import org.joval.util.JOVALMsg;

/**
 * Evaluates RegistryTest OVAL tests.
 *
 * @author David A. Solin
 * @version %I% %G%
 */
public class RegistryAdapter extends BaseRegkeyAdapter<RegistryItem> {
    // Implement IAdapter

    public Collection<Class> init(IBaseSession session) {
	Collection<Class> classes = new ArrayList<Class>();
	if (session instanceof IWindowsSession) {
	    super.init((IWindowsSession)session);
	    classes.add(RegistryObject.class);
	}
	return classes;
    }

    // Protected

    protected Class getItemClass() {
	return RegistryItem.class;
    }

    @Override
    protected List<ISearchable.ICondition> getConditions(ObjectType obj) throws CollectException, PatternSyntaxException {
	List<ISearchable.ICondition> conditions = new ArrayList<ISearchable.ICondition>();
	RegistryObject rObj = (RegistryObject)obj;
	if (rObj.isSetName() && rObj.getName().getValue() != null && rObj.getName().getValue().getValue() != null) {
	    String name = (String)rObj.getName().getValue().getValue();
	    OperationEnumeration op = rObj.getName().getValue().getOperation();
	    switch(op) {
	      case EQUALS:
		conditions.add(new ISearchable.GenericCondition(FIELD_VALUE, TYPE_EQUALITY, name));
		break;
	      case PATTERN_MATCH:
		conditions.add(new ISearchable.GenericCondition(FIELD_VALUE, TYPE_PATTERN, Pattern.compile(name)));
		break;
	      default:
		String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_UNSUPPORTED_OPERATION, op);
		throw new CollectException(msg, FlagEnumeration.NOT_COLLECTED);
	    }
	}
	return conditions;
    }

    protected Collection<RegistryItem> getItems(ObjectType obj, ItemType it, IKey key, IRequestContext rc) throws Exception {
	if (it instanceof RegistryItem) {
	    RegistryItem base = (RegistryItem)it;
	    RegistryObject rObj = (RegistryObject)obj;
	    Collection<RegistryItem> items = new ArrayList<RegistryItem>();

	    if (rObj.getName() == null || rObj.getName().getValue() == null) {
		items.add(getItem(base, key));
	    } else {
		OperationEnumeration op = rObj.getName().getValue().getOperation();
		switch(op) {
		  case EQUALS:
		    items.add(getItem(base, key.getValue((String)rObj.getName().getValue().getValue())));
		    break;

		  case PATTERN_MATCH:
		    try {
			Pattern p = Pattern.compile((String)rObj.getName().getValue().getValue());
			for (IValue value : key.listValues(p)) {
			    items.add(getItem(base, value));
			}
		    } catch (PatternSyntaxException e) {
			MessageType msg = Factories.common.createMessageType();
			msg.setLevel(MessageLevelEnumeration.ERROR);
			msg.setValue(JOVALMsg.getMessage(JOVALMsg.ERROR_PATTERN, e.getMessage()));
			rc.addMessage(msg);
			session.getLogger().warn(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
		    }
		    break;

		  default:
		    String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_UNSUPPORTED_OPERATION, op);
		    throw new CollectException(msg, FlagEnumeration.NOT_COLLECTED);
		}
	    }
	    return items;
	}
	String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_UNSUPPORTED_ITEM, it.getClass().getName());
	throw new CollectException(msg, FlagEnumeration.ERROR);
    }

    @Override
    protected List<InputStream> getPowershellModules() {
	return Arrays.asList(getClass().getResourceAsStream("Registry.psm1"));
    }

    // Private

    private RegistryItem getItem(RegistryItem base, IKey key) throws Exception {
	RegistryItem item = Factories.sc.windows.createRegistryItem();
	item.setHive(base.getHive());
	boolean win32 = false;
	if (base.isSetWindowsView()) {
	    win32 = "32_bit".equals(base.getWindowsView().getValue());
	    item.setWindowsView(base.getWindowsView());
	}

	EntityItemIntType lastWriteTimeType = Factories.sc.core.createEntityItemIntType();
	lastWriteTimeType.setDatatype(SimpleDatatypeEnumeration.INT.value());
	try {
	    StringBuffer sb = new StringBuffer("Get-RegKeyLastWriteTime -Hive ").append(key.getHive().getName());
	    if (key.getPath() != null) {
		sb.append(" -Subkey \"").append(key.getPath()).append("\"");
	    }
	    sb.append(" | %{$_.ToFileTimeUtc()}");
	    IWindowsSession.View view = win32 ? IWindowsSession.View._32BIT : session.getNativeView();
	    lastWriteTimeType.setValue(getRunspace(view).invoke(sb.toString()));
	} catch (Exception e) {
	    session.getLogger().warn(JOVALMsg.getMessage(JOVALMsg.ERROR_EXCEPTION), e);
	    lastWriteTimeType.setStatus(StatusEnumeration.ERROR);
	}
	item.setLastWriteTime(lastWriteTimeType);

	if (key.getPath() == null) {
	    return item;
	}
	item.setKey(base.getKey());
	item.setStatus(StatusEnumeration.EXISTS);
	return item;
    }

    /**
     * Get an item given an IKey and name.
     */
    private RegistryItem getItem(RegistryItem base, IValue value) throws Exception {
	RegistryItem item = getItem(base, value.getKey());

	EntityItemStringType nameType = Factories.sc.core.createEntityItemStringType();
	nameType.setValue(value.getName());
	item.setName(Factories.sc.windows.createRegistryItemName(nameType));

	Collection<EntityItemAnySimpleType> values = new ArrayList<EntityItemAnySimpleType>();
	EntityItemRegistryTypeType typeType = Factories.sc.windows.createEntityItemRegistryTypeType();
	switch (value.getType()) {
	  case REG_SZ: {
	    EntityItemAnySimpleType valueType = Factories.sc.core.createEntityItemAnySimpleType();
	    valueType.setValue(((IStringValue)value).getData());
	    valueType.setDatatype(SimpleDatatypeEnumeration.STRING.value());
	    values.add(valueType);
	    typeType.setValue("reg_sz");
	    break;
	  }

	  case REG_EXPAND_SZ: {
	    EntityItemAnySimpleType valueType = Factories.sc.core.createEntityItemAnySimpleType();
	    valueType.setValue(((IExpandStringValue)value).getExpandedData(session.getEnvironment()));
	    valueType.setDatatype(SimpleDatatypeEnumeration.STRING.value());
	    values.add(valueType);
	    typeType.setValue("reg_expand_sz");
	    break;
	  }

	  case REG_DWORD: {
	    EntityItemAnySimpleType valueType = Factories.sc.core.createEntityItemAnySimpleType();
	    valueType.setValue(Integer.toString(((IDwordValue)value).getData()));
	    valueType.setDatatype(SimpleDatatypeEnumeration.INT.value());
	    values.add(valueType);
	    typeType.setValue("reg_dword");
	    break;
	  }

	  case REG_QWORD: {
	    EntityItemAnySimpleType valueType = Factories.sc.core.createEntityItemAnySimpleType();
	    valueType.setValue(Long.toString(((IQwordValue)value).getData()));
	    valueType.setDatatype(SimpleDatatypeEnumeration.INT.value());
	    values.add(valueType);
	    typeType.setValue("reg_qword");
	    break;
	  }

	  case REG_BINARY: {
	    EntityItemAnySimpleType valueType = Factories.sc.core.createEntityItemAnySimpleType();
	    byte[] data = ((IBinaryValue)value).getData();
	    StringBuffer sb = new StringBuffer();
	    for (int i=0; i < data.length; i++) {
			sb.append(LittleEndian.toHexString(data[i]));
	    }
	    valueType.setValue(sb.toString());
	    valueType.setDatatype(SimpleDatatypeEnumeration.BINARY.value());
	    values.add(valueType);
	    typeType.setValue("reg_binary");
	    break;
	  }

	  case REG_MULTI_SZ: {
	    String[] sVals = ((IMultiStringValue)value).getData();
	    if (sVals == null) {
		EntityItemAnySimpleType valueType = Factories.sc.core.createEntityItemAnySimpleType();
		valueType.setDatatype(SimpleDatatypeEnumeration.STRING.value());
		valueType.setStatus(StatusEnumeration.DOES_NOT_EXIST);
		values.add(valueType);
	    } else {
		for (int i=0; i < sVals.length; i++) {
		    EntityItemAnySimpleType valueType = Factories.sc.core.createEntityItemAnySimpleType();
		    valueType.setDatatype(SimpleDatatypeEnumeration.STRING.value());
		    valueType.setValue(sVals[i]);
		    values.add(valueType);
		}
	    }
	    typeType.setValue("reg_multi_sz");
	    break;
	  }

	  case REG_NONE:
	    typeType.setValue("reg_none");
	    break;

	  default:
	    String msg = JOVALMsg.getMessage(JOVALMsg.ERROR_WINREG_VALUETOSTR, value.getKey().toString(),
					     value.getName(), value.getClass().getName());
	    throw new CollectException(msg, FlagEnumeration.NOT_COLLECTED);
	}
	item.setType(typeType);
	if (values.size() > 0) {
	    item.getValue().addAll(values);
	}
	return item;
    }
}
