/**
 * Copyright (c) 2010-2019 by the respective copyright holders.
 * <p>
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.philipstv.internal.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.http.ParseException;
import org.eclipse.smarthome.core.library.types.RawType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.philipstv.internal.ConnectionManager;
import org.openhab.binding.philipstv.internal.handler.PhilipsTvHandler;
import org.openhab.binding.philipstv.internal.service.api.PhilipsTvService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.openhab.binding.philipstv.internal.PhilipsTvBindingConstants.CHANNEL_APP_ICON;
import static org.openhab.binding.philipstv.internal.PhilipsTvBindingConstants.CHANNEL_APP_NAME;
import static org.openhab.binding.philipstv.internal.PhilipsTvBindingConstants.GET_AVAILABLE_APP_LIST_PATH;
import static org.openhab.binding.philipstv.internal.PhilipsTvBindingConstants.GET_CURRENT_APP_PATH;
import static org.openhab.binding.philipstv.internal.PhilipsTvBindingConstants.LAUNCH_APP_PATH;
import static org.openhab.binding.philipstv.internal.PhilipsTvBindingConstants.SLASH;
import static org.openhab.binding.philipstv.internal.PhilipsTvBindingConstants.TV_NOT_LISTENING_MSG;
import static org.openhab.binding.philipstv.internal.PhilipsTvBindingConstants.TV_OFFLINE_MSG;

/**
 * The {@link AppService} is responsible for handling key code commands, which emulate a button
 * press on a remote control.
 *
 * @author Benjamin Meyer - Initial contribution
 */
public class AppService implements PhilipsTvService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    // Label , Entry<PackageName,ClassName> of App
    private Map<String, Map.Entry<String, String>> availableApps;

    private String currentPackageName = "";

    private final ConnectionManager connectionManager;

    public AppService(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void handleCommand(String channel, Command command, PhilipsTvHandler handler) {
        try {
            synchronized (this) { // TODO: avoids multiple inits at startup
                if (isAvailableAppListEmpty()) {
                    availableApps = getAvailableAppListFromTv();
                    handler.updateChannelStateDescription(CHANNEL_APP_NAME, availableApps.keySet().stream()
                            .collect(Collectors.toMap(Function.identity(), Function.identity())));
                }
            }
            if (command instanceof RefreshType) {
                // Get current App name
                String packageName = getCurrentApp();
                if (currentPackageName.equals(packageName)) {
                    return;
                } else {
                    currentPackageName = packageName;
                }
                Optional<Map.Entry<String, Map.Entry<String, String>>> app = availableApps.entrySet().stream().filter(
                        e -> e.getValue().getKey().equalsIgnoreCase(packageName)).findFirst();
                if (app.isPresent()) {
                    Map.Entry<String, Map.Entry<String, String>> appEntry = app.get();
                    handler.postUpdateChannel(CHANNEL_APP_NAME, new StringType(appEntry.getKey()));
                    // Get icon for current App
                    RawType image = getIconForApp(appEntry.getValue().getKey(), appEntry.getValue().getValue());
                    handler.postUpdateChannel(CHANNEL_APP_ICON, (image != null) ? image : UnDefType.UNDEF);
                } else { // NA
                    handler.postUpdateChannel(CHANNEL_APP_NAME, new StringType(packageName));
                    handler.postUpdateChannel(CHANNEL_APP_ICON, UnDefType.UNDEF);
                }
            } else if (command instanceof StringType) {
                if (availableApps.containsKey(command.toString())) {
                    launchApp(command);
                } else {
                    logger.warn("The given App with Name: {} couldnt be found in the local App List from the tv.",
                            command);
                }
            } else {
                logger.warn("Unknown command: {} for Channel {}", command, channel);
            }
        } catch (Exception e) {
            if (isTvOfflineException(e)) {
                logger.warn("Could not execute command for apps, the TV is offline.");
                handler.postUpdateThing(ThingStatus.OFFLINE, ThingStatusDetail.NONE, TV_OFFLINE_MSG);
            } else if (isTvNotListeningException(e)) {
                handler.postUpdateThing(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        TV_NOT_LISTENING_MSG);
            } else {
                logger.warn("Error occurred during handling of command for apps: {}", e.getMessage(), e);
            }
        }
    }

    private boolean isAvailableAppListEmpty() {
        return (availableApps == null) || availableApps.isEmpty();
    }

    private void launchApp(Command command) throws IOException {
        Map.Entry<String, String> app = availableApps.get(command.toString());

        // Build up app launch json in format:
        // { "intent": { "action": "empty", "component": { "className": "com.spotify.tv.android.SpotifyTVActivity",
        // "packageName": "com.spotify.tv.android" }} }
        JsonObject appLaunch = new JsonObject();
        JsonObject intent = new JsonObject();
        intent.addProperty("action", "empty");

        JsonObject component = new JsonObject();
        component.addProperty("packageName", app.getKey());
        component.addProperty("className", app.getValue());
        intent.add("component", component);
        appLaunch.add("intent", intent);

        logger.debug("App Launch json: {}", appLaunch);
        connectionManager.doHttpsPost(LAUNCH_APP_PATH, appLaunch.toString());
    }

    private String getCurrentApp() throws IOException, ParseException, JsonSyntaxException {
        String jsonContent = connectionManager.doHttpsGet(GET_CURRENT_APP_PATH);
        JsonObject jsonObject = new JsonParser().parse(jsonContent).getAsJsonObject();
        JsonObject componentJson = jsonObject.get("component").getAsJsonObject();
        return componentJson.get("packageName").getAsString();
    }

    private RawType getIconForApp(String packageName, String className) throws IOException {
        String pathForIcon = String.format("%s%s-%s%sicon", SLASH, className, packageName, SLASH);
        byte[] icon = connectionManager.doHttpsGetForImage(
                String.format("%s%s", GET_AVAILABLE_APP_LIST_PATH, pathForIcon));
        if ((icon != null) && (icon.length > 0)) {
            return new RawType(icon, "image/png");
        } else {
            return null;
        }
    }

    private Map<String, Map.Entry<String, String>> getAvailableAppListFromTv() throws IOException {
        JsonArray applicationsJsonArray;

        String jsonContent = connectionManager.doHttpsGet(GET_AVAILABLE_APP_LIST_PATH);
        applicationsJsonArray = (JsonArray) new JsonParser().parse(jsonContent).getAsJsonObject().get("applications");

        Map<String, Map.Entry<String, String>> appsMap = new ConcurrentHashMap<>();

        for (JsonElement jsonElement : applicationsJsonArray) {
            JsonObject app = jsonElement.getAsJsonObject();
            JsonObject intentJson = app.getAsJsonObject("intent");
            JsonObject componentJson = intentJson.getAsJsonObject("component");

            String label = app.get("label").getAsString();
            String packageName = componentJson.get("packageName").getAsString();
            String className = componentJson.get("className").getAsString();

            appsMap.put(label, new AbstractMap.SimpleEntry<String, String>(packageName, className));
        }

        logger.debug("Apps added: {}", appsMap.size());
        if (logger.isTraceEnabled()) {
            appsMap.keySet().forEach(app -> logger.trace("App found: {}", app));
        }
        return appsMap;
    }

    public void clearAvailableAppList() {
        if (availableApps != null) {
            availableApps.clear();
        }
    }
}
