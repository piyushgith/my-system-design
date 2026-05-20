-- Champion model (simulated LR — replace with real ONNX path in prod)
INSERT INTO model_registry (model_id, model_version, model_type, role, challenger_traffic_pct,
    product_types, s3_model_path, feature_order, approved_by, notes)
VALUES (
    'mdl-champion-0001',
    'sim-v1.0',
    'SIMULATED_LR',
    'CHAMPION',
    0,
    'PERSONAL_LOAN,CREDIT_CARD',
    's3://credit-models/sim-v1.0.onnx',
    'bureau.cibil_score,bureau.dpd_last_6m,bureau.credit_utilization,bureau.inquiry_count_last_90d,behavior.upi_txn_count_last_30d,behavior.avg_monthly_credit',
    'system',
    'Simulated logistic regression — MVP placeholder for real ONNX model'
);

-- Challenger model (10% traffic — slightly re-calibrated weights)
INSERT INTO model_registry (model_id, model_version, model_type, role, challenger_traffic_pct,
    product_types, s3_model_path, feature_order, approved_by, notes)
VALUES (
    'mdl-challenger-0001',
    'sim-v1.1',
    'SIMULATED_LR',
    'CHALLENGER',
    10,
    'PERSONAL_LOAN,CREDIT_CARD',
    's3://credit-models/sim-v1.1.onnx',
    'bureau.cibil_score,bureau.dpd_last_6m,bureau.credit_utilization,bureau.inquiry_count_last_90d,behavior.upi_txn_count_last_30d,behavior.avg_monthly_credit',
    'system',
    'Challenger — more lenient on utilization, testing vs champion'
);

-- Feature catalogue
INSERT INTO feature_definitions VALUES ('fd-001', 'bureau.cibil_score',              'BUREAU',     'CIBIL score (300-900); 0 if thin file',          'CIBIL',   720, 'FLOAT',   300,  'feature:{user_id}:bureau.cibil_score',              FALSE, NULL);
INSERT INTO feature_definitions VALUES ('fd-002', 'bureau.dpd_last_6m',              'BUREAU',     'Max days past due in last 6 months',             'CIBIL',   720, 'INT',     0,    'feature:{user_id}:bureau.dpd_last_6m',              FALSE, NULL);
INSERT INTO feature_definitions VALUES ('fd-003', 'bureau.credit_utilization',       'BUREAU',     'Revolving credit utilization ratio (0-1)',        'CIBIL',   720, 'FLOAT',   0.5,  'feature:{user_id}:bureau.credit_utilization',       FALSE, NULL);
INSERT INTO feature_definitions VALUES ('fd-004', 'bureau.inquiry_count_last_90d',   'BUREAU',     'Hard credit inquiries in last 90 days',           'CIBIL',   720, 'INT',     0,    'feature:{user_id}:bureau.inquiry_count_last_90d',   FALSE, NULL);
INSERT INTO feature_definitions VALUES ('fd-005', 'behavior.upi_txn_count_last_30d', 'BEHAVIORAL', 'UPI transaction count last 30 days',             'LEDGER',  24,  'INT',     0,    'feature:{user_id}:behavior.upi_txn_count_last_30d', FALSE, NULL);
INSERT INTO feature_definitions VALUES ('fd-006', 'behavior.avg_monthly_credit',     'BEHAVIORAL', 'Average monthly credit inflow (INR)',             'LEDGER',  24,  'FLOAT',   0,    'feature:{user_id}:behavior.avg_monthly_credit',     FALSE, NULL);
INSERT INTO feature_definitions VALUES ('fd-007', 'meta.is_thin_file',               'META',       'True if user has no bureau history',              'INTERNAL',720, 'BOOLEAN', NULL, 'feature:{user_id}:meta.is_thin_file',               FALSE, NULL);
INSERT INTO feature_definitions VALUES ('fd-008', 'meta.bureau_as_of',               'META',       'Timestamp of last bureau data refresh',           'INTERNAL',720, 'STRING',  NULL, 'feature:{user_id}:meta.bureau_as_of',               FALSE, NULL);
INSERT INTO feature_definitions VALUES ('fd-009', 'meta.behavior_as_of',             'META',       'Timestamp of last behavioral feature refresh',    'INTERNAL',24,  'STRING',  NULL, 'feature:{user_id}:meta.behavior_as_of',             FALSE, NULL);
