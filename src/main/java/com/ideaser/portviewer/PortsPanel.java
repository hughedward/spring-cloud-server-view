package com.ideaser.portviewer;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ProjectTopics;
import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.TableSpeedSearch;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

public final class PortsPanel extends SimpleToolWindowPanel implements Disposable {

    private static final int REFRESH_DEBOUNCE_MS = 400;
    private static final int STATUS_POLL_MS = 5000;
    private static final JBColor RUNNING_GREEN = new JBColor(0x1E8E3E, 0x57C36A);
    private static final int APP_NAME_COLUMN = PortsTableModel.APP_NAME_COLUMN;
    private static final int PORT_COLUMN = PortsTableModel.PORT_COLUMN;
    private static final int STATUS_COLUMN = PortsTableModel.STATUS_COLUMN;
    private static final int MODULE_COLUMN = PortsTableModel.MODULE_COLUMN;
    private static final int BUILD_COLUMN = PortsTableModel.BUILD_FILE_COLUMN;
    private static final int CONFIG_COLUMN = PortsTableModel.CONFIG_FILE_COLUMN;

    private final Project project;
    private final PortsTableModel model = new PortsTableModel();
    private final JBTable table = new JBTable(model) {
        @Override
        public Component prepareRenderer(TableCellRenderer renderer, int viewRow, int viewColumn) {
            Component c = super.prepareRenderer(renderer, viewRow, viewColumn);
            if (viewRow >= 0 && viewRow < getRowCount()) {
                ModulePortInfo info = model.rowAt(convertRowIndexToModel(viewRow));
                if (convertColumnIndexToModel(viewColumn) == PORT_COLUMN) {
                    if (model.isConflict(info)) {
                        c.setForeground(JBColor.RED);
                    } else if (info.defaultPort()) {
                        c.setForeground(JBColor.GRAY);
                    } else {
                        c.setForeground(getForeground());
                    }
                } else if (convertColumnIndexToModel(viewColumn) == STATUS_COLUMN) {
                    c.setForeground(model.isRunning(info) ? RUNNING_GREEN : JBColor.GRAY);
                } else if (isActionableColumn(convertColumnIndexToModel(viewColumn), info)) {
                    c.setForeground(JBColor.BLUE); // link-like: single click navigates
                }
            }
            return c;
        }
    };
    private final JBLabel statusLabel = new JBLabel("扫描中…");
    private final Alarm refreshAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private final Alarm statusAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);

    public PortsPanel(@NotNull Project project) {
        super(true, true);
        this.project = project;

        table.setAutoCreateRowSorter(true);
        table.setRowHeight(JBUI.scale(26));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setStriped(true);
        table.getEmptyText().setText("未发现 Spring 配置文件（application*/bootstrap*）");
        setColumnWidths();
        new TableSpeedSearch(table);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(@NotNull MouseEvent e) {
                if (e.getClickCount() != 1) {
                    return; // single-click navigation only
                }
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewColumn = table.columnAtPoint(e.getPoint());
                if (viewRow < 0 || viewRow >= table.getRowCount() || viewColumn < 0) {
                    return;
                }
                ModulePortInfo info = model.rowAt(table.convertRowIndexToModel(viewRow));
                switch (table.convertColumnIndexToModel(viewColumn)) {
                    case MODULE_COLUMN -> revealInProjectView(info);
                    case APP_NAME_COLUMN -> openAndReveal(info.configFile(), "spring", "application", "name");
                    case PORT_COLUMN -> openAndReveal(info.configFile(), "server", "port");
                    case BUILD_COLUMN -> openAndReveal(info.buildFile());
                    case CONFIG_COLUMN -> openAndReveal(info.configFile());
                    default -> { /* Profile / 运行状态列：无对应跳转位置 */ }
                }
            }
        });

        JPanel root = new JPanel(new BorderLayout());
        root.add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER);
        statusLabel.setBorder(JBUI.Borders.empty(4, 8));
        root.add(statusLabel, BorderLayout.SOUTH);
        setContent(root);
        setToolbar(buildToolbar().getComponent());
        setComponentPopupMenu(buildPopupMenu());

        listenForChanges();
        refresh();
    }

    // ------------------------------------------------------------------- toolbar

    private ActionToolbar buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new RefreshAction());
        group.addSeparator();
        group.add(new OpenConfigAction());
        group.add(new OpenInBrowserAction());
        group.add(new CopyPortAction());
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("PortsToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    private JPopupMenu buildPopupMenu() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new OpenConfigAction());
        group.add(new OpenInBrowserAction());
        group.add(new CopyPortAction());
        return ActionManager.getInstance()
                .createActionPopupMenu("PortsPopup", group)
                .getComponent();
    }

    private void setColumnWidths() {
        int[] widths = {200, 150, 80, 110, 80, 120, 190};
        for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    // ------------------------------------------------------------------ refresh

    private void refresh() {
        if (project.isDisposed()) {
            return;
        }
        ReadAction.<List<ModulePortInfo>>nonBlocking(() -> PortScanner.scan(project))
                .expireWith(this)
                .finishOnUiThread(ModalityState.any(), rows -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    model.update(rows);
                    updateStatusLabel();
                    pollStatusNow(); // liveness reflects the fresh rows immediately
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void updateStatusLabel() {
        if (model.getRowCount() == 0) {
            statusLabel.setText("未发现配置");
            return;
        }
        statusLabel.setText(model.getRowCount() + " 条 · 运行中 " + model.runningCount()
                + " · 端口冲突 " + model.conflictPortCount());
    }

    // ----------------------------------------------------------- liveness polling

    /** Checks effective ports off-EDT, updates the model, then re-schedules itself. */
    private void pollStatusNow() {
        if (project.isDisposed() || Disposer.isDisposed(this)) {
            return;
        }
        List<Integer> ports = model.effectivePorts();
        if (ports.isEmpty()) {
            scheduleNextStatusPoll();
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Set<Integer> listening = PortLivenessChecker.check(ports);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed() || Disposer.isDisposed(this)) {
                    return;
                }
                model.updateRunning(listening);
                updateStatusLabel();
            }, ModalityState.any());
            scheduleNextStatusPoll();
        });
    }

    private void scheduleNextStatusPoll() {
        statusAlarm.cancelAllRequests();
        statusAlarm.addRequest(this::pollStatusNow, STATUS_POLL_MS);
    }

    private void scheduleRefresh() {
        refreshAlarm.cancelAllRequests();
        refreshAlarm.addRequest(this::refresh, REFRESH_DEBOUNCE_MS);
    }

    private void listenForChanges() {
        MessageBusConnection connection = project.getMessageBus().connect(this);
        connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    String path = event.getPath();
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    if (ConfigFileParser.isConfigFile(name)) {
                        scheduleRefresh();
                        return;
                    }
                }
            }
        });
        connection.subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
            @Override
            public void rootsChanged(@NotNull ModuleRootEvent event) {
                scheduleRefresh();
            }
        });
        connection.subscribe(RunManagerListener.TOPIC, new RunManagerListener() {
            @Override
            public void runConfigurationAdded(@NotNull RunnerAndConfigurationSettings settings) {
                scheduleRefresh();
            }

            @Override
            public void runConfigurationRemoved(@NotNull RunnerAndConfigurationSettings settings) {
                scheduleRefresh();
            }

            @Override
            public void runConfigurationChanged(@NotNull RunnerAndConfigurationSettings settings) {
                scheduleRefresh(); // -Dserver.port overrides may have changed
            }
        });
        // instant state sync in BOTH directions: native run/stop buttons and our own
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override
            public void processStarted(@NotNull String executorId,
                                       @NotNull ExecutionEnvironment environment,
                                       @NotNull ProcessHandler handler) {
                ApplicationManager.getApplication().invokeLater(PortsPanel.this::pollStatusNow,
                        ModalityState.any());
            }

            @Override
            public void processTerminated(@NotNull String executorId,
                                          @NotNull ExecutionEnvironment environment,
                                          @NotNull ProcessHandler handler,
                                          int exitCode) {
                ApplicationManager.getApplication().invokeLater(PortsPanel.this::pollStatusNow,
                        ModalityState.any());
            }
        });
    }


    // ------------------------------------------------------------------- actions

    @Nullable
    private ModulePortInfo selectedRow() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0 || viewRow >= table.getRowCount()) {
            return null;
        }
        return model.rowAt(table.convertRowIndexToModel(viewRow));
    }

    private boolean isActionableColumn(int modelColumn, @NotNull ModulePortInfo info) {
        return modelColumn == MODULE_COLUMN
                || (modelColumn == APP_NAME_COLUMN && info.appName() != null && info.configFile() != null)
                || (modelColumn == PORT_COLUMN && info.configFile() != null)
                || (modelColumn == BUILD_COLUMN && info.buildFileName() != null)
                || (modelColumn == CONFIG_COLUMN && info.configFile() != null);
    }

    private void openConfig(@NotNull ModulePortInfo info) {
        openAndReveal(info.configFile());
    }

    /**
     * Opens the file in the editor — at the given key path when found — and
     * simultaneously selects the file in the Project view.
     */
    private void openAndReveal(@Nullable VirtualFile file, @NotNull String... keyPath) {
        if (file == null || !file.isValid()) {
            return;
        }
        int offset = ReadAction.compute(() -> {
            int position = keyPath.length == 0
                    ? -1
                    : ConfigFileParser.offsetOfKey(project, file, keyPath);
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile != null) {
                ProjectView.getInstance(project).select(psiFile, file, false);
            }
            return position;
        });
        OpenFileDescriptor descriptor = offset >= 0
                ? new OpenFileDescriptor(project, file, offset)
                : new OpenFileDescriptor(project, file);
        descriptor.navigate(true);
    }

    /** Reveals the module's root directory in the Project view. */
    private void revealInProjectView(@NotNull ModulePortInfo info) {
        VirtualFile dir = info.moduleDir();
        if (dir == null || !dir.isValid()) {
            return;
        }
        ReadAction.run(() -> {
            PsiDirectory psiDirectory = PsiManager.getInstance(project).findDirectory(dir);
            if (psiDirectory != null) {
                ProjectView.getInstance(project).select(psiDirectory, dir, true);
            }
        });
    }

    private abstract class PortsAction extends AnAction {
        PortsAction(@Nullable String text, @Nullable String description, @Nullable Icon icon) {
            super(text, description, icon);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class RefreshAction extends PortsAction {
        RefreshAction() {
            super("刷新", "重新扫描所有模块的端口", AllIcons.Actions.Refresh);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!project.isDisposed());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            refresh();
        }
    }

    private final class OpenConfigAction extends PortsAction {
        OpenConfigAction() {
            super("打开配置文件", "在编辑器中打开该端口所在的配置文件", AllIcons.FileTypes.Any_type);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            ModulePortInfo selected = selectedRow();
            e.getPresentation().setEnabled(selected != null
                    && selected.configFile() != null
                    && selected.configFile().isValid());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ModulePortInfo selected = selectedRow();
            if (selected != null) {
                openConfig(selected);
            }
        }
    }

    private final class OpenInBrowserAction extends PortsAction {
        OpenInBrowserAction() {
            super("浏览器打开", "访问 http://localhost:<端口>", AllIcons.General.Web);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            ModulePortInfo selected = selectedRow();
            e.getPresentation().setEnabled(selected != null && selected.port() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ModulePortInfo selected = selectedRow();
            if (selected == null || selected.port() == null) {
                return;
            }
            StringBuilder url = new StringBuilder("http://localhost:").append(selected.port());
            if (selected.contextPath() != null && !selected.contextPath().isBlank()) {
                String ctx = selected.contextPath();
                url.append(ctx.startsWith("/") ? ctx : "/").append(ctx);
            }
            try {
                Desktop.getDesktop().browse(URI.create(url.toString()));
            } catch (IOException | UnsupportedOperationException ex) {
                statusLabel.setText("无法打开浏览器: " + ex.getMessage());
            }
        }
    }

    private final class CopyPortAction extends PortsAction {
        CopyPortAction() {
            super("复制端口", "复制端口号到剪贴板", AllIcons.Actions.Copy);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            ModulePortInfo selected = selectedRow();
            e.getPresentation().setEnabled(selected != null && selected.port() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ModulePortInfo selected = selectedRow();
            if (selected != null && selected.port() != null) {
                CopyPasteManager.getInstance()
                        .setContents(new StringSelection(String.valueOf(selected.port())));
            }
        }
    }

    // ------------------------------------------------------------------- dispose

    @Override
    public void dispose() {
        refreshAlarm.cancelAllRequests();
        statusAlarm.cancelAllRequests();
    }
}
