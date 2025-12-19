package com.schemadiff.core;

import com.schemadiff.model. DatabaseMetadata;
import java. sql.Connection;

public abstract class MetadataExtractor {
    public abstract DatabaseMetadata extract(Connection conn) throws Exception;
}