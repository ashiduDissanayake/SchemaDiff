package com.wso2.migration.checker.core;

import com.wso2.migration.checker.model.DriftReport;
import com.wso2.migration.checker.model.LogicDrift;
import com.wso2.migration.checker.model.StructuralDrift;

public class DriftReportBuilder {

    public DriftReport build(StructuralDrift structuralDrift, LogicDrift logicDrift) {
        return new DriftReport(structuralDrift, logicDrift);
    }
}