package com.wso2.migration.checker.core;

import com.example. driftmaster.model.DriftReport;
import com.example. driftmaster.model.LogicDrift;
import com. example.driftmaster.model. StructuralDrift;

public class DriftReportBuilder {

    public DriftReport build(StructuralDrift structuralDrift, LogicDrift logicDrift) {
        return new DriftReport(structuralDrift, logicDrift);
    }
}