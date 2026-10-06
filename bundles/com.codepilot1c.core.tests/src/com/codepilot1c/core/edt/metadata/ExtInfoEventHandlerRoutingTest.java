/*******************************************************************************
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Copyright (C) 2026 codepilot1c-edt contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License v3.0 as published by the
 * Free Software Foundation.
 ******************************************************************************/
package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.DynamicListTableExtInfo;
import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.form.model.ExtInfo;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormVisualEntity;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.form.service.FormItemInformationService;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;

/**
 * Feedback 2026-06-24 Issue 3 (live-confirmed 2026-10-06 on the sandbox): {@code set_item
 * handlers:[{event:OnGetDataAtServer}]} on a dynamic-list table reported success but wrote the
 * handler into the table's OWN {@code <handlers>}, which the platform never calls; EDT keeps it in
 * {@code <extInfo xsi:type="form:DynamicListTableExtInfo"><handlers>}. Root cause: EDT's
 * {@code getAllowedEvents(FormVisualEntity)} appends the extInfo's events to the item's own list,
 * so a top-level-first lookup always won. The stub below reproduces that superset exactly.
 */
public class ExtInfoEventHandlerRoutingTest {

    @Test
    public void dynamicListTableEventLandsInTheExtInfoContainer() throws Exception {
        Table table = FormFactory.eINSTANCE.createTable();
        DynamicListTableExtInfo extInfo = FormFactory.eINSTANCE.createDynamicListTableExtInfo();
        table.setExtInfo(extInfo);
        Event selection = event("Selection"); //$NON-NLS-1$
        Event onGetData = event("OnGetDataAtServer"); //$NON-NLS-1$
        // A stale handler from the old mis-routing, sitting inert in the table's own list.
        table.getHandlers().add(handler(onGetData, "Stale")); //$NON-NLS-1$

        EdtMetadataService service = serviceWith(new SupersetInfoService(
                List.of(selection), List.of(onGetData), extInfo));
        invokeBinding(service, table, List.of(
                Map.of("event", "OnGetDataAtServer", "handler", "ListOnGetDataAtServer"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                Map.of("event", "Selection", "handler", "ListSelection"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("REGRESSION: an extInfo-declared event must be written into the extInfo's " //$NON-NLS-1$
                + "handlers, where the platform reads it", //$NON-NLS-1$
                List.of("OnGetDataAtServer=ListOnGetDataAtServer"), describe(extInfo)); //$NON-NLS-1$
        assertEquals("the item's own events stay on the item, and the stale mis-routed " //$NON-NLS-1$
                + "OnGetDataAtServer is removed from it", //$NON-NLS-1$
                List.of("Selection=ListSelection"), describe(table)); //$NON-NLS-1$
    }

    // ---- support -------------------------------------------------------------------------------

    /** Mirrors EDT: the item's allowed events = its own type events + its extInfo's events. */
    private static final class SupersetInfoService extends FormItemInformationService {
        private final List<Event> own;
        private final List<Event> ext;
        private final ExtInfo extInfo;

        SupersetInfoService(List<Event> own, List<Event> ext, ExtInfo extInfo) {
            this.own = own;
            this.ext = ext;
            this.extInfo = extInfo;
        }

        @Override
        public List<Event> getAllowedEvents(FormVisualEntity entity) {
            List<Event> all = new ArrayList<>(own);
            all.addAll(ext);
            return all;
        }

        @Override
        public List<Event> getAllowedEvents(ExtInfo info) {
            return ext;
        }

        @Override
        public ExtInfo getExtensionInfo(EObject object) {
            return extInfo;
        }
    }

    private static Event event(String name) {
        Event event = McoreFactory.eINSTANCE.createEvent();
        event.setName(name);
        return event;
    }

    private static com._1c.g5.v8.dt.form.model.EventHandler handler(Event event, String name) {
        com._1c.g5.v8.dt.form.model.EventHandler handler = FormFactory.eINSTANCE.createEventHandler();
        handler.setEvent(event);
        handler.setName(name);
        return handler;
    }

    private static List<String> describe(EventHandlerContainer container) {
        List<String> out = new ArrayList<>();
        container.getHandlers().forEach(h -> out.add(h.getEvent().getName() + "=" + h.getName())); //$NON-NLS-1$
        return out;
    }

    private static EdtMetadataService serviceWith(FormItemInformationService infoService) throws Exception {
        EdtMetadataService service = new EdtMetadataService();
        Field field = EdtMetadataService.class.getDeclaredField("formItemInformationService"); //$NON-NLS-1$
        field.setAccessible(true);
        field.set(service, infoService);
        return service;
    }

    private static void invokeBinding(EdtMetadataService service, EObject target, Object handlers)
            throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("applyEventHandlersBinding", //$NON-NLS-1$
                EObject.class, EventHandlerContainer.class, Object.class);
        method.setAccessible(true);
        try {
            method.invoke(service, target, target, handlers);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }
}
