package com.ideaser.portviewer;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class PortViewerToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        PortsPanel panel = new PortsPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, null, false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
