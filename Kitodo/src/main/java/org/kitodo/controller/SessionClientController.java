/*
 * (c) Kitodo. Key to digital objects e. V. <contact@kitodo.org>
 *
 * This file is part of the Kitodo project.
 *
 * It is licensed under GNU General Public License version 3 or later.
 *
 * For the full copyright and license information, please read the
 * GPL3-License.txt file that was distributed with this source code.
 */

package org.kitodo.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.enterprise.context.RequestScoped;
import javax.inject.Named;

import org.kitodo.data.database.beans.Client;
import org.kitodo.data.database.beans.Project;
import org.kitodo.services.ServiceManager;
import org.primefaces.context.RequestContext;

@Named("SessionClientController")
@RequestScoped
public class SessionClientController {

    private transient ServiceManager serviceManager = new ServiceManager();

    private Client selectedClient;

    /**
     * Gets the name of the current session client. In case that no session client
     * has been set, an empty string is returned and a dialog to select a client is
     * shown.
     * 
     * @return The current session clients name or empty string case that no session
     *         client has been set.
     */
    public String getCurrentSessionClientName() {
        if (Objects.nonNull(getCurrentSessionClient())) {
            return getCurrentSessionClient().getName();
        } else {
            if (shouldUserChangeSessionClient()) {
                showClientSelectDialog();
            }

            if (setSessionClientIfUserHasOnlyOne()) {
                return getCurrentSessionClient().getName();
            }
            return "";
        }
    }

    private boolean setSessionClientIfUserHasOnlyOne() {
        List<Client> clients = getClientsOfUserAssignedProjects();
        if (clients.size() == 1) {
            setSessionClient(clients.get(0));
            return true;
        }
        return false;
    }

    /**
     * The conditions when user need to select a session client is configured in
     * this method.
     * 
     * @return True if the session client select dialog should by displayed to the
     *         current user
     */
    public boolean shouldUserChangeSessionClient() {

        //No change if user is admin.
        if (serviceManager.getSecurityAccessService().isAdmin()) {
            return false;
        }

        //No change if we have only one client for selection.
        List<Client> clients = getClientsOfUserAssignedProjects();
        if (clients.size() == 1) {
            return false;
        }
        return true;
    }

    private void showClientSelectDialog() {
        RequestContext.getCurrentInstance().execute("PF('selectClientDialog').show();");
    }

    private Client getCurrentSessionClient() {
        return serviceManager.getUserService().getSessionClientOfAuthenticatedUser();
    }

    public void setSelectedClientAsSessionClient() {
        setSessionClient(selectedClient);
    }

    /**
     * Gets selectedClient.
     *
     * @return The selectedClient.
     */
    public Client getSelectedClient() {
        return selectedClient;
    }

    /**
     * Sets selectedClient.
     *
     * @param selectedClient
     *            The selectedClient.
     */
    public void setSelectedClient(Client selectedClient) {
        this.selectedClient = selectedClient;
    }

    /**
     * Sets the given client object as new session client.
     * 
     * @param sessionClient
     *            The client object that is to be the new session client.
     */
    public void setSessionClient(Client sessionClient) {
        serviceManager.getUserService().getAuthenticatedUser().setSessionClient(sessionClient);
    }

    /**
     * Gets all clients of user assigned projects.
     *
     * @return The list of clients.
     */
    public List<Client> getClientsOfUserAssignedProjects() {
        List<Project> projects = serviceManager.getUserService().getAuthenticatedUser().getProjects();
        List<Client> clients = new ArrayList<>();

        for (Project project : projects) {
            Client client = project.getClient();
            if (!clients.contains(client)) {
                clients.add(client);
            }
        }
        return clients;
    }
}
