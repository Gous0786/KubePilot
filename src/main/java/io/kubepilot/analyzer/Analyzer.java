package io.kubepilot.analyzer;

import io.kubepilot.common.ClusterSnapshot;
import io.kubepilot.common.Finding;


import java.util.List;


public interface Analyzer {
    String id();
    
    List<Finding> analyze(ClusterSnapshot snapshot);
}

