#!/bin/bash
# Test script to verify PostgreSQL extractor extracts sequences, functions, and triggers

echo "Testing PostgreSQL Extractor with Sequences, Functions, and Triggers"
echo "======================================================================"

# Create a test SQL file with sequences, functions, and triggers
cat > /tmp/test_pg_features.sql << 'EOF'
-- Drop existing objects if they exist
DROP TRIGGER IF EXISTS update_timestamp_trigger ON test_table;
DROP FUNCTION IF EXISTS update_timestamp();
DROP TABLE IF EXISTS test_table;
DROP SEQUENCE IF EXISTS test_sequence;

-- Create a sequence
CREATE SEQUENCE test_sequence
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 999999
    CACHE 1;

-- Create a function
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create a table
CREATE TABLE test_table (
    id INTEGER DEFAULT nextval('test_sequence'),
    name VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- Create a trigger
CREATE TRIGGER update_timestamp_trigger
    BEFORE UPDATE ON test_table
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

-- Verify objects were created
SELECT 'Sequences:' as object_type, sequence_name as name FROM information_schema.sequences WHERE sequence_schema = 'public';
SELECT 'Functions:' as object_type, routine_name as name FROM information_schema.routines WHERE routine_schema = 'public' AND routine_type = 'FUNCTION';
SELECT 'Triggers:' as object_type, trigger_name as name FROM information_schema.triggers WHERE trigger_schema = 'public';
SELECT 'Tables:' as object_type, table_name as name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
EOF

echo ""
echo "Created test SQL file: /tmp/test_pg_features.sql"
echo ""
echo "To test with PostgreSQL:"
echo "1. Load the test schema:"
echo "   psql -d WSO2AM_DB -f /tmp/test_pg_features.sql"
echo ""
echo "2. Run the extractor:"
echo "   mvn21 exec:java -Dexec.mainClass=\"com.schemadiff.Main\" -Dexec.args=\"--db-type postgres --jdbc-url jdbc:postgresql://localhost:5432/WSO2AM_DB --username <user> --password <pass> --schema public\""
echo ""
echo "3. Check the output for:"
echo "   - Sequences: 1 (test_sequence)"
echo "   - Functions: 1 (update_timestamp)"
echo "   - Triggers: 1 (update_timestamp_trigger)"
echo ""
echo "Test SQL file contents:"
echo "----------------------"
cat /tmp/test_pg_features.sql

