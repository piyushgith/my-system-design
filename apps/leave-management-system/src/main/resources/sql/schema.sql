-- 1. Create the EMPLOYEES table (Self-referencing for manager_id)
CREATE TABLE IF NOT EXISTS employees (
    id BIGSERIAL PRIMARY KEY,
    emp_id VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    department VARCHAR(100) NOT NULL,
    manager_id BIGINT REFERENCES employees(id),
    role VARCHAR(50) NOT NULL, -- Roles: EMPLOYEE, MANAGER, ADMIN
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE
);

---

-- 2. Create the LEAVE_TYPES table
CREATE TABLE IF NOT EXISTS leave_types (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE, -- e.g., PERSONAL, SICK, COMP_OFF
    description VARCHAR(500),
    annual_allocation INT NOT NULL, -- Days per year
    carryforward_limit INT, -- Max days that can be carried forward
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

---

-- 3. Create the LEAVE_BALANCES table (Uses a Generated Column for balance)
CREATE TABLE IF NOT EXISTS leave_balances (
    id BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees(id),
    leave_type_id BIGINT NOT NULL REFERENCES leave_types(id),
    year INT NOT NULL,
    allocated_days INT NOT NULL,
    used_days NUMERIC DEFAULT 0, -- Use NUMERIC to track fractional days (e.g., half-days)
    carryforward_days NUMERIC DEFAULT 0,
    -- Balance is calculated automatically by the database
    balance_days NUMERIC GENERATED ALWAYS AS (allocated_days + carryforward_days - used_days) STORED,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (employee_id, leave_type_id, year)
);

---

-- 4. Create the LEAVE_REQUESTS table
CREATE TABLE IF NOT EXISTS leave_requests (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(50) UNIQUE NOT NULL,
    employee_id BIGINT NOT NULL REFERENCES employees(id),
    leave_type_id BIGINT NOT NULL REFERENCES leave_types(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    number_of_days NUMERIC NOT NULL, -- Use NUMERIC to support half-day requests
    reason VARCHAR(500),
    status VARCHAR(20) DEFAULT 'PENDING', -- Statuses: PENDING, APPROVED, REJECTED, WITHDRAWN
    approved_by BIGINT REFERENCES employees(id),
    rejection_reason VARCHAR(500),
    withdrawn_on TIMESTAMP,
    requested_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    approved_on TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

---

-- 5. Create the NOTIFICATIONS table
CREATE TABLE IF NOT EXISTS notifications (
    id BIGSERIAL PRIMARY KEY,
    recipient_id BIGINT NOT NULL REFERENCES employees(id),
    leave_request_id BIGINT NOT NULL REFERENCES leave_requests(id),
    notification_type VARCHAR(50) NOT NULL, -- Types: REQUEST_CREATED, APPROVED, REJECTED, etc.
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP
);

---

-- 6. Create the AUDIT_LOGS table
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,
    leave_request_id BIGINT NOT NULL REFERENCES leave_requests(id),
    action VARCHAR(50) NOT NULL, -- Actions: CREATED, APPROVED, REJECTED, WITHDRAWN
    performed_by BIGINT NOT NULL REFERENCES employees(id),
    old_status VARCHAR(20),
    new_status VARCHAR(20),
    remarks VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 7 . Create the USERS table for authentication and authorization
CREATE TABLE users (
    id VARCHAR(255) PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    active BOOLEAN DEFAULT true NOT NULL,
    roles TEXT[] DEFAULT ARRAY[]::TEXT[] NOT NULL
);

-- Create indexes for better query performance
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);