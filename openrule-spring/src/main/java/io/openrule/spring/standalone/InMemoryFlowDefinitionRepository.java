package io.openrule.spring.standalone;

import io.openrule.core.definition.FlowDefinition;
import io.openrule.spring.port.FlowDefinitionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内仓储：flowId → (version → 定义)。版本自增、唯一 enabled（见 Global Constraints）。 */
public class InMemoryFlowDefinitionRepository implements FlowDefinitionRepository {

    private final Map<String, TreeMap<Integer, FlowDefinition>> store = new ConcurrentHashMap<>();

    @Override
    public synchronized FlowDefinition save(FlowDefinition def) {
        TreeMap<Integer, FlowDefinition> versions =
                store.computeIfAbsent(def.getFlowId(), k -> new TreeMap<>());
        int next = versions.isEmpty() ? 1 : versions.lastKey() + 1;
        versions.values().forEach(d -> d.setEnabled(false));
        def.setVersion(next);
        def.setEnabled(true);
        versions.put(next, def);
        return def;
    }

    @Override
    public synchronized void enable(String flowId, int version) {
        TreeMap<Integer, FlowDefinition> versions = store.get(flowId);
        if (versions == null || !versions.containsKey(version)) {
            return;
        }
        versions.values().forEach(d -> d.setEnabled(false));
        versions.get(version).setEnabled(true);
    }

    @Override
    public Optional<FlowDefinition> findActiveByFlowId(String flowId) {
        TreeMap<Integer, FlowDefinition> versions = store.get(flowId);
        if (versions == null) {
            return Optional.empty();
        }
        return versions.values().stream().filter(FlowDefinition::isEnabled).findFirst();
    }

    @Override
    public Optional<FlowDefinition> findByFlowIdAndVersion(String flowId, int version) {
        TreeMap<Integer, FlowDefinition> versions = store.get(flowId);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    @Override
    public List<Integer> listVersions(String flowId) {
        TreeMap<Integer, FlowDefinition> versions = store.get(flowId);
        return versions == null ? List.of() : new ArrayList<>(versions.keySet());
    }

    @Override
    public synchronized List<FlowDefinition> findAllVersions(String flowId) {
        TreeMap<Integer, FlowDefinition> versions = store.get(flowId);
        return versions == null ? List.of() : new ArrayList<>(versions.values());
    }
}
