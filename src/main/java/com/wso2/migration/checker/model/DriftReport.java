package com.wso2.migration.checker.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.HashMap;
import java.util.Map;

public class DriftReport {
    private StructuralDrift structuralDrift;
    private LogicDrift logicDrift;
    private Map<String, Integer> summary;

    public DriftReport(StructuralDrift structuralDrift, LogicDrift logicDrift) {
        this.structuralDrift = structuralDrift;
        this.logicDrift = logicDrift;
        this. summary = buildSummary();
    }

    private Map<String, Integer> buildSummary() {
        Map<String, Integer> summary = new HashMap<>();
        summary.put("missingTables", structuralDrift.getMissingTables().size());
        summary.put("missingColumns", structuralDrift.getMissingColumns().size());
        summary.put("missingIndexes", structuralDrift.getMissingIndexes().size());
        summary.put("unexpectedTables", structuralDrift.getUnexpectedTables().size());
        summary.put("changedTables", structuralDrift.getChangedTables().size());
        summary.put("missingLogic", logicDrift.getMissingLogic().size());
        summary.put("changedLogic", logicDrift.getChangedLogic().size());
        return summary;
    }

    public boolean hasDrift() {
        return structuralDrift.hasDrift() || logicDrift.hasDrift();
    }

    public String toJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature. INDENT_OUTPUT);
        
        Map<String, Object> report = new HashMap<>();
        report.put("structuralDrift", structuralDrift);
        report.put("logicDrift", logicDrift);
        report.put("summary", summary);
        
        return mapper.writeValueAsString(report);
    }

    public StructuralDrift getStructuralDrift() { return structuralDrift; }
    public LogicDrift getLogicDrift() { return logicDrift; }
    public Map<String, Integer> getSummary() { return summary; }
}