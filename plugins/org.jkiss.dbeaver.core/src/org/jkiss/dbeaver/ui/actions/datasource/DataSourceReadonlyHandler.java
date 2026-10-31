/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.actions.datasource;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.core.CoreMessages;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCProtectedReadOnly;
import org.jkiss.dbeaver.model.rm.RMConstants;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.jobs.DisconnectJob;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.actions.AbstractDataSourceHandler;
import org.jkiss.dbeaver.ui.internal.UIConnectionMessages;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.util.Map;

public class DataSourceReadonlyHandler extends AbstractDataSourceHandler implements IElementUpdater {

    private DataSourceDescriptor currentDescriptor = null;
    private boolean changingMode;

    @Override
    @Nullable
    public Object execute(@Nullable ExecutionEvent event) throws ExecutionException {
        final DataSourceDescriptor dataSourceContainer = currentDescriptor;
        if (dataSourceContainer != null) {
            if (changingMode || Job.getJobManager().find(dataSourceContainer).length > 0) {
                MessageDialog.openInformation(UIUtils.getActiveWorkbenchShell(), CoreMessages.protected_readonly_title,
                    CoreMessages.protected_readonly_busy);
                DataSourceToolbarUtils.triggerRefreshReadonlyElement();
                return null;
            }
            int choice = new MessageDialog(UIUtils.getActiveWorkbenchShell(), CoreMessages.protected_readonly_title, null,
                CoreMessages.protected_readonly_description + (dataSourceContainer.isConnected()
                    ? "\n\n" + CoreMessages.protected_readonly_reconnect : ""), MessageDialog.QUESTION,
                new String[] {CoreMessages.protected_readonly_readwrite, CoreMessages.protected_readonly_standard,
                    CoreMessages.protected_readonly_protected, CoreMessages.protected_readonly_cancel}, 3).open();
            if (choice < 0 || choice > 2) {
                DataSourceToolbarUtils.triggerRefreshReadonlyElement();
                return null;
            }
            if (dataSourceContainer.isConnectionProtectedReadOnly() == (choice == 2)
                && dataSourceContainer.isConnectionReadOnly() == (choice != 0)) {
                DataSourceToolbarUtils.triggerRefreshReadonlyElement();
                return null;
            }
            if (dataSourceContainer.isConnected()) {
                changingMode = true;
                // Keep the old policy until all existing sessions have been disconnected.
                // The standard disconnect flow asks about unsaved edits and active transactions.
                DataSourceHandler.disconnectDataSource(dataSourceContainer, () -> {
                    Job job = Job.getJobManager().currentJob();
                    IStatus status = job instanceof DisconnectJob disconnectJob
                        ? disconnectJob.getConnectStatus() : Status.CANCEL_STATUS;
                    UIUtils.asyncExec(() -> {
                        if (status == null || !status.isOK() || dataSourceContainer.isConnected()) {
                            changingMode = false;
                            DataSourceToolbarUtils.triggerRefreshReadonlyElement();
                            if (status != null && status.getSeverity() == IStatus.ERROR) {
                                DBWorkbench.getPlatformUI().showError(CoreMessages.protected_readonly_title, null, status);
                            }
                            return;
                        }
                        applyMode(dataSourceContainer, choice);
                        DataSourceHandler.connectToDataSource(null, dataSourceContainer, result -> UIUtils.asyncExec(() -> {
                            changingMode = false;
                            DataSourceToolbarUtils.triggerRefreshReadonlyElement();
                            if (!result.isOK() && result.getSeverity() != IStatus.CANCEL) {
                                DBWorkbench.getPlatformUI().showError(CoreMessages.protected_readonly_title, null, result);
                            }
                        }));
                    });
                });
                // Canceling the save/transaction dialog schedules no disconnect job.
                if (Job.getJobManager().find(dataSourceContainer).length == 0) {
                    changingMode = false;
                }
            } else {
                applyMode(dataSourceContainer, choice);
            }
            DataSourceToolbarUtils.triggerRefreshReadonlyElement();
        }
        return null;
    }

    private static void applyMode(@NotNull DataSourceDescriptor container, int choice) {
        container.getPreferenceStore().setValue(DBCProtectedReadOnly.CONFIG_KEY, choice == 2);
        container.setConnectionReadOnly(choice != 0);
        if (container.getProject().hasRealmPermission(RMConstants.PERMISSION_PROJECT_DATASOURCES_EDIT)) {
            container.persistConfiguration();
        }
        DataSourceToolbarUtils.triggerRefreshReadonlyElement();
    }

    @Override
    public void updateElement(@NotNull UIElement element, @Nullable Map parameters) {
        DBPDataSourceContainer container = getActiveDataSourceContainer(element.getServiceLocator().getService(IWorkbenchWindow.class));
        DataSourceDescriptor descriptor = container instanceof DataSourceDescriptor ? (DataSourceDescriptor) container : null;
        if (descriptor != currentDescriptor) {
            currentDescriptor = descriptor;
        }
        if (currentDescriptor != null) {
            boolean isReadonly = currentDescriptor.isConnectionReadOnly();
            if (currentDescriptor.isConnectionProtectedReadOnly()) {
                element.setTooltip(currentDescriptor.isConnected()
                    ? CoreMessages.protected_readonly_active : CoreMessages.protected_readonly_pending);
                element.setIcon(DBeaverIcons.getImageDescriptor(currentDescriptor.isConnected()
                    ? UIIcon.SQL_PROTECTED_READONLY : UIIcon.SQL_READONLY));
            } else if (isReadonly) {
                element.setTooltip(NLS.bind(CoreMessages.toolbar_checkbox_connection_not_readonly_tooltip, currentDescriptor.getName()));
                element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.SQL_READONLY));
            } else {
                element.setTooltip(NLS.bind(CoreMessages.toolbar_checkbox_connection_readonly_tooltip, currentDescriptor.getName()));
                element.setIcon(DBeaverIcons.getImageDescriptor(DBIcon.TREE_UNLOCKED));
            }
            element.setChecked(isReadonly);
        } else {
            element.setChecked(false);
            element.setTooltip(UIConnectionMessages.dialog_connection_wizard_final_checkbox_connection_readonly);
            element.setIcon(DBeaverIcons.getImageDescriptor(DBIcon.TREE_UNLOCKED));
        }
    }


    @Nullable
    private static DBPDataSourceContainer getActiveDataSourceContainer(@NotNull IWorkbenchWindow window) {
        if (window != null) {
            IWorkbenchPage page = window.getActivePage();
            if (page != null) {
                IWorkbenchPart activePart = page.getActivePart();
                DBPDataSourceContainer container = getDataSourceContainerFromPart(activePart);
                if (container != null) {
                    return container;
                }
            }

            ISelection selection = window.getSelectionService().getSelection();
            if (selection != null) {
                DBSObject selectedObject = NavigatorUtils.getSelectedObject(selection);
                if (selectedObject instanceof DBPDataSourceContainer) {
                    return (DBPDataSourceContainer) selectedObject;
                } else if (selectedObject != null) {
                    DBPDataSource dataSource = selectedObject.getDataSource();
                    if (dataSource != null) {
                        return dataSource.getContainer();
                    }
                }
            }
        }
        return null;
    }

}
