/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.download;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.ManuallyCreatedModpackException;
import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.mod.Modpack;
import org.jackhuang.hmcl.mod.ModpackFile;
import org.jackhuang.hmcl.mod.ModpackManifest;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.RequiredValidator;
import org.jackhuang.hmcl.ui.construct.Validator;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.jackhuang.hmcl.util.io.FileUtils;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import static org.jackhuang.hmcl.util.Lang.tryCast;
import static org.jackhuang.hmcl.util.Logging.LOG;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public final class LocalModpackPage extends ModpackPage {

    private final BooleanProperty installAsVersion = new SimpleBooleanProperty(true);
    private Modpack manifest = null;
    private Charset charset;

    public LocalModpackPage(WizardController controller) {
        super(controller);

        Profile profile = (Profile) controller.getSettings().get("PROFILE");

        Optional<String> name = tryCast(controller.getSettings().get(MODPACK_NAME), String.class);
        if (name.isPresent()) {
            txtModpackName.setText(name.get());
            txtModpackName.setDisable(true);
        } else {
            FXUtils.onChangeAndOperate(installAsVersion, installAsVersion -> {
                if (installAsVersion) {
                    txtModpackName.getValidators().setAll(
                            new RequiredValidator(),
                            new Validator(i18n("install.new_game.already_exists"), str -> !profile.getRepository().versionIdConflicts(str)),
                            new Validator(i18n("install.new_game.malformed"), HMCLGameRepository::isValidVersionId));
                } else {
                    txtModpackName.getValidators().setAll(
                            new RequiredValidator(),
                            new Validator(i18n("install.new_game.already_exists"), str -> !ModpackHelper.isExternalGameNameConflicts(str) && Profiles.getProfiles().stream().noneMatch(p -> p.getName().equals(str))),
                            new Validator(i18n("install.new_game.malformed"), HMCLGameRepository::isValidVersionId));
                }
            });
        }

        File selectedFile;
        Optional<File> filePath = tryCast(controller.getSettings().get(MODPACK_FILE), File.class);
        if (filePath.isPresent()) {
            selectedFile = filePath.get();
        } else {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(i18n("modpack.choose"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(i18n("modpack"), "*.zip"));
            selectedFile = chooser.showOpenDialog(Controllers.getStage());
            if (selectedFile == null) {
                controller.onEnd();
                return;
            }

            controller.getSettings().put(MODPACK_FILE, selectedFile);
        }

        showSpinner();
        Task.supplyAsync(() -> CompressingUtils.findSuitableEncoding(selectedFile.toPath()))
                .thenApplyAsync(encoding -> {
                    charset = encoding;
                    manifest = ModpackHelper.readModpackManifest(selectedFile.toPath(), encoding);
                    pendingOptionalFiles(manifest);
                    return manifest;
                }).whenComplete(Schedulers.javafx(), (_unused, exception) -> {
                    if (exception instanceof ManuallyCreatedModpackException) {
                        hideSpinner();
                        lblName.setText(selectedFile.getName());
                        installAsVersion.set(false);
                        lblModpackLocation.setText(selectedFile.getAbsolutePath());

                        if (!name.isPresent()) {
                            // trim: https://github.com/HMCL-dev/HMCL/issues/962
                            txtModpackName.setText(FileUtils.getNameWithoutExtension(selectedFile));
                        }

                        Controllers.confirm(i18n("modpack.type.manual.warning"), i18n("install.modpack"), MessageDialogPane.MessageType.WARNING,
                                () -> {},
                                controller::onEnd);

                        controller.getSettings().put(MODPACK_MANUALLY_CREATED, true);
                    } else if (exception != null) {
                        LOG.log(Level.WARNING, "Failed to read modpack manifest", exception);
                        Controllers.dialog(i18n("modpack.task.install.error"), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                        Platform.runLater(controller::onEnd);
                    } else {
                        hideSpinner();
                        controller.getSettings().put(MODPACK_MANIFEST, manifest);
                        lblName.setText(manifest.getName());
                        lblVersion.setText(manifest.getVersion());
                        lblAuthor.setText(manifest.getAuthor());

                        lblModpackLocation.setText(selectedFile.getAbsolutePath());

                        if (!name.isPresent()) {
                            // trim: https://github.com/HMCL-dev/HMCL/issues/962
                            txtModpackName.setText(manifest.getName().trim());
                        }
                    }
                }).start();
    }

    private void pendingOptionalFiles(Modpack manifest) {
        waitingForOptionalFiles.set(true);
        if (!(manifest.getManifest() instanceof ModpackManifest.SupportOptional)) {
            optionalFiles.setOptionalFileList(Collections.emptyList());
            return;
        }
        optionalFiles.setPending();
        Task.supplyAsync(() -> manifest.setManifest(manifest.getManifest().getProvider().loadFiles(manifest.getManifest())))
                .whenComplete(Schedulers.javafx(), (manifest1, exception) -> {
                    List<? extends ModpackFile> files = ((ModpackManifest.SupportOptional) manifest1.getManifest()).getFiles();
                    optionalFiles.setOptionalFileList(files);
                    waitingForOptionalFiles.set(false);
                    if (exception != null || files.stream().anyMatch(s -> s.isOptional() && (s.getMod() == null || s.getFileName() == null))) {
                        LOG.log(Level.WARNING, "Failed to load optional files", exception);
                        optionalFiles.setRetry(() -> pendingOptionalFiles(manifest));
                    }
                }).start();
    }

    @Override
    public void cleanup(Map<String, Object> settings) {
        settings.remove(MODPACK_FILE);
    }

    protected void onInstall() {
        if (!txtModpackName.validate()) return;
        controller.getSettings().put(MODPACK_NAME, txtModpackName.getText());
        controller.getSettings().put(MODPACK_CHARSET, charset);
        controller.getSettings().put(MODPACK_SELECTED_FILES, optionalFiles.getSelected());
        controller.onFinish();
    }

    protected void onDescribe() {
        if (manifest != null) {
            FXUtils.showWebDialog(i18n("modpack.description"), manifest.getDescription());
        }
    }

    public static final String MODPACK_FILE = "MODPACK_FILE";
    public static final String MODPACK_NAME = "MODPACK_NAME";
    public static final String MODPACK_MANIFEST = "MODPACK_MANIFEST";
    public static final String MODPACK_CHARSET = "MODPACK_CHARSET";
    public static final String MODPACK_MANUALLY_CREATED = "MODPACK_MANUALLY_CREATED";

    public static final String MODPACK_SELECTED_FILES = "MODPACK_SELECTED_FILES";
}
