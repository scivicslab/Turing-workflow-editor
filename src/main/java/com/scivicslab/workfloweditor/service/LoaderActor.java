package com.scivicslab.workfloweditor.service;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.workflow.DynamicActorLoaderActor;
import com.scivicslab.pojoactor.workflow.IIActorRef;
import com.scivicslab.pojoactor.workflow.IIActorSystem;

/**
 * IIActorRef wrapper for DynamicActorLoaderActor.
 * Enables dynamic JAR loading and actor creation from workflows.
 */
public class LoaderActor extends IIActorRef<DynamicActorLoaderActor> {

    public LoaderActor(String name, IIActorSystem system) {
        super(name, new DynamicActorLoaderActor(system), system);
    }

    @Action("loadJar")
    public ActionResult loadJar(String args) {
        return object.callByActionName("loadJar", args);
    }

    @Action("createChild")
    public ActionResult createChild(String args) {
        return object.callByActionName("createChild", args);
    }

    @Action("loadFromJar")
    public ActionResult loadFromJar(String args) {
        return object.callByActionName("loadFromJar", args);
    }

    @Action("listLoadedJars")
    public ActionResult listLoadedJars(String args) {
        return object.callByActionName("listLoadedJars", args);
    }

    @Action("listProviders")
    public ActionResult listProviders(String args) {
        return object.callByActionName("listProviders", args);
    }
}
