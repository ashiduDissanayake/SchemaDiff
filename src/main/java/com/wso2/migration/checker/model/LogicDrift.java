package com.wso2.migration.checker.model;

import java.util.ArrayList;
import java.util.List;

public class LogicDrift {
    private List<String> missingLogic = new ArrayList<>();
    private List<String> unexpectedLogic = new ArrayList<>();
    private List<String> changedLogic = new ArrayList<>();

    public boolean hasDrift() {
        return !missingLogic.isEmpty() || !unexpectedLogic.isEmpty() || ! changedLogic.isEmpty();
    }

    public List<String> getMissingLogic() { return missingLogic; }
    public List<String> getUnexpectedLogic() { return unexpectedLogic; }
    public List<String> getChangedLogic() { return changedLogic; }
}