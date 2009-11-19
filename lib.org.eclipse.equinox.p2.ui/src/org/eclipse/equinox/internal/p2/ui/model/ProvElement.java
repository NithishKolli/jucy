/*******************************************************************************
 *  Copyright (c) 2007, 2009 IBM Corporation and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.ui.model;

import org.eclipse.core.runtime.*;
import org.eclipse.equinox.internal.p2.ui.ProvUIActivator;
import org.eclipse.equinox.internal.provisional.p2.ui.ProvUI;
import org.eclipse.equinox.internal.provisional.p2.ui.ProvUIImages;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.progress.IDeferredWorkbenchAdapter;
import org.eclipse.ui.statushandlers.StatusManager;

/**
 * Generic element that represents a provisioning element in
 * a viewer.  
 * 
 * @since 3.4
 *
 */
public abstract class ProvElement implements IWorkbenchAdapter, IAdaptable {

	private Object parent;

	public ProvElement(Object parent) {
		this.parent = parent;
	}

	public Object getAdapter(Class adapter) {
		if (adapter == IWorkbenchAdapter.class)
			return this;
		if ((adapter == IDeferredWorkbenchAdapter.class) && this instanceof IDeferredWorkbenchAdapter)
			return this;
		return null;
	}

	/**
	 * Return a string id of the image that should be used to show the specified
	 * object. Returning null indicates that no image should be used.
	 * 
	 * @param obj
	 *            the object whose image id is requested
	 * @return the string id of the image in the provisioning image registry, or
	 *         <code>null</code> if no image should be shown.
	 */
	protected String getImageId(Object obj) {
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.model.IWorkbenchAdapter#getImageDescriptor(java.lang.Object)
	 */
	public ImageDescriptor getImageDescriptor(Object object) {
		String id = getImageId(object);
		if (id == null) {
			return null;
		}
		ImageDescriptor desc = ProvUIImages.getImageDescriptor(id);
		if (desc == null)
			desc = JFaceResources.getImageRegistry().getDescriptor(id);
		return desc;
	}

	/**
	 * Return the image that should be used to show the specfied object. The
	 * image is managed by an image registry and should not be freed.
	 * 
	 * @param object
	 *            the object whose image id is requested
	 * @return the string id of the image in the provisioning image registry
	 * 
	 */
	public Image getImage(Object object) {
		String id = getImageId(object);
		if (id == null) {
			return null;
		}
		Image img = ProvUIImages.getImage(id);
		if (img == null)
			img = JFaceResources.getImageRegistry().get(id);
		return img;
	}

	protected void handleException(Exception e, String message) {
		if (message == null) {
			message = e.getMessage();
		}
		IStatus status = new Status(IStatus.ERROR, ProvUIActivator.PLUGIN_ID, 0, message, e);
		ProvUI.reportStatus(status, StatusManager.LOG);
	}

	public boolean hasChildren(Object o) {
		if (this instanceof IDeferredWorkbenchAdapter)
			return ((IDeferredWorkbenchAdapter) this).isContainer();
		Object[] children = getChildren(o);
		if (children == null) {
			return false;
		}
		return children.length > 0;
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.ui.model.IWorkbenchAdapter#getParent(java.lang.Object)
	 */
	public Object getParent(Object o) {
		return parent;
	}
}