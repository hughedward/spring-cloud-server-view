package com.ideaser.portviewer;

import org.jetbrains.annotations.NotNull;

import javax.swing.table.AbstractTableModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class PortsTableModel extends AbstractTableModel {

    static final int MODULE_COLUMN = 0;
    static final int APP_NAME_COLUMN = 1;
    static final int PROFILE_COLUMN = 2;
    static final int PORT_COLUMN = 3;
    static final int STATUS_COLUMN = 4;
    static final int BUILD_FILE_COLUMN = 5;
    static final int CONFIG_FILE_COLUMN = 6;

    private static final String[] COLUMNS =
            {"模块", "服务名", "Profile", "端口", "运行状态", "构建文件", "配置文件"};

    private List<ModulePortInfo> rows = List.of();
    private Set<Integer> conflictPorts = Set.of();
    private Set<Integer> listeningPorts = Set.of();

    void update(@NotNull List<ModulePortInfo> newRows) {
        Map<Integer, Integer> portCounts = new HashMap<>();
        for (ModulePortInfo row : newRows) {
            if (row.port() != null) {
                portCounts.merge(row.port(), 1, Integer::sum);
            }
        }
        this.rows = List.copyOf(newRows);
        this.conflictPorts = portCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        fireTableDataChanged();
    }

    /** Called from the periodic liveness poller with the ports that answered. */
    void updateRunning(@NotNull Set<Integer> nowListening) {
        this.listeningPorts = Set.copyOf(nowListening);
        fireTableDataChanged();
    }

    ModulePortInfo rowAt(int modelRow) {
        return rows.get(modelRow);
    }

    boolean isConflict(@NotNull ModulePortInfo row) {
        return row.port() != null && conflictPorts.contains(row.port());
    }

    boolean isRunning(@NotNull ModulePortInfo row) {
        return row.port() != null && listeningPorts.contains(row.port());
    }

    int runningCount() {
        return (int) rows.stream().filter(this::isRunning).count();
    }

    int conflictPortCount() {
        return conflictPorts.size();
    }

    /** Distinct ports that need a liveness check. */
    List<Integer> effectivePorts() {
        return rows.stream()
                .map(ModulePortInfo::port)
                .filter(port -> port != null)
                .distinct()
                .toList();
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int modelRow, int column) {
        ModulePortInfo info = rows.get(modelRow);
        return switch (column) {
            case MODULE_COLUMN -> info.moduleName();
            case 1 -> info.appName() == null ? "" : info.appName();
            case 2 -> info.displayProfile();
            case PORT_COLUMN -> info.displayPort();
            case STATUS_COLUMN -> info.port() == null
                    ? "" : (isRunning(info) ? "● 运行中" : "○ 未运行");
            case 5 -> info.buildFileName() == null ? "" : info.buildFileName();
            case 6 -> info.configFileName();
            default -> "";
        };
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return false;
    }
}
