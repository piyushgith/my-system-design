-- Insert sample data for Leave Management System

-- 1. Insert EMPLOYEES
INSERT INTO employees (emp_id, name, email, department, manager_id, role, is_active) VALUES
('EMP001', 'Alice Johnson', 'alice.johnson@company.com', 'Engineering', NULL, 'ADMIN', TRUE),
('EMP002', 'Bob Smith', 'bob.smith@company.com', 'Engineering', 1, 'MANAGER', TRUE),
('EMP003', 'Carol White', 'carol.white@company.com', 'Engineering', 2, 'EMPLOYEE', TRUE),
('EMP004', 'David Brown', 'david.brown@company.com', 'Engineering', 2, 'EMPLOYEE', TRUE),
('EMP005', 'Eva Martinez', 'eva.martinez@company.com', 'Human Resources', 1, 'MANAGER', TRUE),
('EMP006', 'Frank Davis', 'frank.davis@company.com', 'Human Resources', 5, 'EMPLOYEE', TRUE),
('EMP007', 'Grace Lee', 'grace.lee@company.com', 'Finance', 1, 'MANAGER', TRUE),
('EMP008', 'Henry Wilson', 'henry.wilson@company.com', 'Finance', 7, 'EMPLOYEE', TRUE),
('EMP009', 'Ivy Taylor', 'ivy.taylor@company.com', 'Marketing', 1, 'MANAGER', TRUE),
('EMP010', 'Jack Anderson', 'jack.anderson@company.com', 'Marketing', 9, 'EMPLOYEE', TRUE);

---

-- 2. Insert LEAVE_TYPES
INSERT INTO leave_types (name, description, annual_allocation, carryforward_limit, is_active) VALUES
('PERSONAL', 'Personal/Casual Leave', 12, 5, TRUE),
('SICK', 'Sick Leave', 10, 3, TRUE),
('COMP_OFF', 'Compensatory Off', 5, 2, TRUE),
('MATERNITY', 'Maternity Leave', 90, 0, TRUE),
('PATERNITY', 'Paternity Leave', 30, 0, TRUE),
('BEREAVEMENT', 'Bereavement Leave', 3, 0, TRUE);

---

-- 3. Insert LEAVE_BALANCES for 2024
INSERT INTO leave_balances (employee_id, leave_type_id, year, allocated_days, used_days, carryforward_days) VALUES
-- Alice Johnson (EMP001)
(1, 1, 2024, 12, 3, 2),
(1, 2, 2024, 10, 1, 0),
(1, 3, 2024, 5, 0, 1),

-- Bob Smith (EMP002)
(2, 1, 2024, 12, 4, 3),
(2, 2, 2024, 10, 2, 1),
(2, 3, 2024, 5, 1, 0),

-- Carol White (EMP003)
(3, 1, 2024, 12, 6, 2),
(3, 2, 2024, 10, 0, 0),
(3, 3, 2024, 5, 0, 0),

-- David Brown (EMP004)
(4, 1, 2024, 12, 2, 1),
(4, 2, 2024, 10, 3, 0),
(4, 3, 2024, 5, 2, 0),

-- Eva Martinez (EMP005)
(5, 1, 2024, 12, 5, 2),
(5, 2, 2024, 10, 1, 0),
(5, 3, 2024, 5, 0, 1),

-- Frank Davis (EMP006)
(6, 1, 2024, 12, 3, 1),
(6, 2, 2024, 10, 4, 0),
(6, 3, 2024, 5, 1, 0),

-- Grace Lee (EMP007)
(7, 1, 2024, 12, 7, 2),
(7, 2, 2024, 10, 0, 1),
(7, 3, 2024, 5, 0, 0),

-- Henry Wilson (EMP008)
(8, 1, 2024, 12, 4, 0),
(8, 2, 2024, 10, 2, 0),
(8, 3, 2024, 5, 1, 0),

-- Ivy Taylor (EMP009)
(9, 1, 2024, 12, 2, 3),
(9, 2, 2024, 10, 1, 0),
(9, 3, 2024, 5, 0, 0),

-- Jack Anderson (EMP010)
(10, 1, 2024, 12, 5, 1),
(10, 2, 2024, 10, 3, 0),
(10, 3, 2024, 5, 2, 1);

---

-- 4. Insert LEAVE_REQUESTS
INSERT INTO leave_requests (request_id, employee_id, leave_type_id, start_date, end_date, number_of_days, reason, status, approved_by, requested_on, approved_on) VALUES
('REQ001', 3, 1, '2024-01-15', '2024-01-19', 5, 'Personal vacation', 'APPROVED', 2, '2024-01-10 10:30:00', '2024-01-10 14:00:00'),
('REQ002', 4, 2, '2024-02-05', '2024-02-05', 1, 'Sick leave', 'APPROVED', 2, '2024-02-05 09:00:00', '2024-02-05 09:15:00'),
('REQ003', 6, 1, '2024-03-01', '2024-03-08', 6, 'Extended leave', 'APPROVED', 5, '2024-02-20 11:00:00', '2024-02-20 15:30:00'),
('REQ004', 8, 1, '2024-03-15', '2024-03-15', 0.5, 'Half day leave', 'APPROVED', 7, '2024-03-14 16:00:00', '2024-03-14 16:30:00'),
('REQ005', 10, 2, '2024-04-10', '2024-04-10', 1, 'Sick leave', 'PENDING', NULL, '2024-04-10 08:45:00', NULL),
('REQ006', 3, 3, '2024-04-20', '2024-04-20', 1, 'Comp off - worked on Sunday', 'PENDING', NULL, '2024-04-15 09:00:00', NULL),
('REQ007', 1, 1, '2024-05-01', '2024-05-05', 5, 'Planned vacation', 'APPROVED', NULL, '2024-04-15 13:20:00', '2024-04-15 14:00:00'),
('REQ008', 9, 1, '2024-05-10', '2024-05-10', 0.5, 'Half day personal', 'REJECTED', NULL, '2024-05-08 10:00:00', NULL);

---

-- 5. Insert NOTIFICATIONS
INSERT INTO notifications (recipient_id, leave_request_id, notification_type, title, message, is_read, created_at, read_at) VALUES
(2, 1, 'REQUEST_CREATED', 'New Leave Request', 'Carol White has requested 5 days of PERSONAL leave from 2024-01-15 to 2024-01-19', TRUE, '2024-01-10 10:31:00', '2024-01-10 11:00:00'),
(3, 1, 'APPROVED', 'Leave Request Approved', 'Your leave request for 5 days has been approved', TRUE, '2024-01-10 14:01:00', '2024-01-10 14:15:00'),
(2, 2, 'REQUEST_CREATED', 'New Leave Request', 'David Brown has requested 1 day of SICK leave on 2024-02-05', TRUE, '2024-02-05 09:01:00', '2024-02-05 09:30:00'),
(4, 2, 'APPROVED', 'Leave Request Approved', 'Your leave request for 1 day has been approved', TRUE, '2024-02-05 09:16:00', '2024-02-05 10:00:00'),
(5, 3, 'REQUEST_CREATED', 'New Leave Request', 'Frank Davis has requested 6 days of PERSONAL leave from 2024-03-01 to 2024-03-08', FALSE, '2024-02-20 11:01:00', NULL),
(6, 3, 'APPROVED', 'Leave Request Approved', 'Your leave request for 6 days has been approved', FALSE, '2024-02-20 15:31:00', NULL),
(7, 4, 'REQUEST_CREATED', 'New Leave Request', 'Henry Wilson has requested 0.5 days of PERSONAL leave on 2024-03-15', TRUE, '2024-03-14 16:01:00', '2024-03-14 16:20:00'),
(8, 4, 'APPROVED', 'Leave Request Approved', 'Your half-day leave request has been approved', TRUE, '2024-03-14 16:31:00', '2024-03-14 17:00:00'),
(2, 5, 'REQUEST_CREATED', 'New Leave Request', 'Jack Anderson has requested 1 day of SICK leave on 2024-04-10', FALSE, '2024-04-10 08:46:00', NULL),
(9, 6, 'REQUEST_CREATED', 'New Leave Request', 'Carol White has requested 1 day of COMP_OFF on 2024-04-20', FALSE, '2024-04-15 09:01:00', NULL);

---

-- 6. Insert AUDIT_LOGS
INSERT INTO audit_logs (leave_request_id, action, performed_by, old_status, new_status, remarks) VALUES
(1, 'CREATED', 3, NULL, 'PENDING', 'Leave request created by employee'),
(1, 'APPROVED', 2, 'PENDING', 'APPROVED', 'Request approved by manager Bob Smith'),
(2, 'CREATED', 4, NULL, 'PENDING', 'Leave request created by employee'),
(2, 'APPROVED', 2, 'PENDING', 'APPROVED', 'Request approved by manager Bob Smith'),
(3, 'CREATED', 6, NULL, 'PENDING', 'Leave request created by employee'),
(3, 'APPROVED', 5, 'PENDING', 'APPROVED', 'Request approved by manager Eva Martinez'),
(4, 'CREATED', 8, NULL, 'PENDING', 'Half-day leave request created'),
(4, 'APPROVED', 7, 'PENDING', 'APPROVED', 'Request approved by manager Grace Lee'),
(7, 'CREATED', 1, NULL, 'PENDING', 'Admin leave request created'),
(7, 'APPROVED', 1, 'PENDING', 'APPROVED', 'Request auto-approved for admin');



