# Leave Management System Design

## System Overview
A Spring Reactive leave management system with H2 database backend. The system allows employees to request different types of leaves, managers to approve/reject requests, and automated notifications for both parties.

---

## Database Design

### Tables

#### 1. **employees**
Stores employee information.

```sql
CREATE TABLE employees (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    emp_id VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    department VARCHAR(100) NOT NULL,
    manager_id BIGINT,
    role VARCHAR(50) NOT NULL, -- EMPLOYEE, MANAGER, ADMIN
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    FOREIGN KEY (manager_id) REFERENCES employees(id)
);
```

#### 2. **leave_types**
Stores different types of leaves available in the system.

```sql
CREATE TABLE leave_types (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE, -- PERSONAL, SPECIAL, COMP_OFF
    description VARCHAR(500),
    annual_allocation INT NOT NULL, -- days per year
    carryforward_limit INT, -- max days that can be carried forward
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### 3. **leave_balances**
Tracks leave balance per employee per leave type per year.

```sql
CREATE TABLE leave_balances (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    leave_type_id BIGINT NOT NULL,
    year INT NOT NULL,
    allocated_days INT NOT NULL,
    used_days INT DEFAULT 0,
    carryforward_days INT DEFAULT 0,
    balance_days INT GENERATED ALWAYS AS (allocated_days + carryforward_days - used_days),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE (employee_id, leave_type_id, year),
    FOREIGN KEY (employee_id) REFERENCES employees(id),
    FOREIGN KEY (leave_type_id) REFERENCES leave_types(id)
);
```

#### 4. **leave_requests**
Stores all leave requests.

```sql
CREATE TABLE leave_requests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(50) UNIQUE NOT NULL,
    employee_id BIGINT NOT NULL,
    leave_type_id BIGINT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    number_of_days INT NOT NULL,
    reason VARCHAR(500),
    status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED, WITHDRAWN
    approved_by BIGINT,
    rejection_reason VARCHAR(500),
    withdrawn_on TIMESTAMP,
    requested_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    approved_on TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (employee_id) REFERENCES employees(id),
    FOREIGN KEY (leave_type_id) REFERENCES leave_types(id),
    FOREIGN KEY (approved_by) REFERENCES employees(id)
);
```

#### 5. **notifications**
Stores notification records for employees and managers.

```sql
CREATE TABLE notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipient_id BIGINT NOT NULL,
    leave_request_id BIGINT NOT NULL,
    notification_type VARCHAR(50) NOT NULL, -- REQUEST_CREATED, APPROVED, REJECTED, WITHDRAWN
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP,
    FOREIGN KEY (recipient_id) REFERENCES employees(id),
    FOREIGN KEY (leave_request_id) REFERENCES leave_requests(id)
);
```

#### 6. **audit_logs**
Tracks all changes to leave requests for audit purposes.

```sql
CREATE TABLE audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    leave_request_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL, -- CREATED, APPROVED, REJECTED, WITHDRAWN
    performed_by BIGINT NOT NULL,
    old_status VARCHAR(20),
    new_status VARCHAR(20),
    remarks VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (leave_request_id) REFERENCES leave_requests(id),
    FOREIGN KEY (performed_by) REFERENCES employees(id)
);
```

---

## API Endpoints

### Base URL: `/api/v1/leave-management`

### Authentication
All endpoints require Bearer token authentication.

---

### 1. Employee Endpoints

#### 1.1 Request Leave
```
POST /leaves/request
Content-Type: application/json

Request Body:
{
    "leaveTypeId": 1,
    "startDate": "2025-12-15",
    "endDate": "2025-12-17",
    "reason": "Personal reasons"
}

Response (201 Created):
{
    "id": 1,
    "requestId": "LR-2025-001",
    "employeeId": 10,
    "leaveType": "PERSONAL",
    "startDate": "2025-12-15",
    "endDate": "2025-12-17",
    "numberOfDays": 3,
    "reason": "Personal reasons",
    "status": "PENDING",
    "requestedOn": "2025-12-05T10:30:00Z",
    "createdAt": "2025-12-05T10:30:00Z"
}
```

#### 1.2 Get My Leave Requests
```
GET /leaves/my-requests?status=PENDING&leaveTypeId=1&page=0&size=10
Authorization: Bearer {token}

Response (200 OK):
{
    "content": [
        {
            "id": 1,
            "requestId": "LR-2025-001",
            "leaveType": "PERSONAL",
            "startDate": "2025-12-15",
            "endDate": "2025-12-17",
            "numberOfDays": 3,
            "status": "PENDING",
            "requestedOn": "2025-12-05T10:30:00Z"
        }
    ],
    "pageNumber": 0,
    "pageSize": 10,
    "totalElements": 1,
    "totalPages": 1
}
```

#### 1.3 Get Leave Balance
```
GET /leaves/balance?year=2025
Authorization: Bearer {token}

Response (200 OK):
{
    "employeeId": 10,
    "year": 2025,
    "leaveBalances": [
        {
            "leaveTypeId": 1,
            "leaveType": "PERSONAL",
            "allocatedDays": 10,
            "usedDays": 3,
            "carriedForwardDays": 2,
            "balanceDays": 9
        },
        {
            "leaveTypeId": 2,
            "leaveType": "SPECIAL",
            "allocatedDays": 5,
            "usedDays": 1,
            "carriedForwardDays": 0,
            "balanceDays": 4
        }
    ]
}
```

#### 1.4 Withdraw Leave Request
```
PUT /leaves/{requestId}/withdraw
Authorization: Bearer {token}

Response (200 OK):
{
    "id": 1,
    "requestId": "LR-2025-001",
    "status": "WITHDRAWN",
    "withdrawnOn": "2025-12-05T11:00:00Z",
    "message": "Leave request withdrawn successfully"
}
```

#### 1.5 Get Leave Request Details
```
GET /leaves/{requestId}
Authorization: Bearer {token}

Response (200 OK):
{
    "id": 1,
    "requestId": "LR-2025-001",
    "employeeId": 10,
    "employeeName": "John Doe",
    "leaveType": "PERSONAL",
    "startDate": "2025-12-15",
    "endDate": "2025-12-17",
    "numberOfDays": 3,
    "reason": "Personal reasons",
    "status": "PENDING",
    "requestedOn": "2025-12-05T10:30:00Z"
}
```

#### 1.6 Get My Notifications
```
GET /notifications?isRead=false&page=0&size=10
Authorization: Bearer {token}

Response (200 OK):
{
    "content": [
        {
            "id": 1,
            "leaveRequestId": 1,
            "notificationType": "REQUEST_CREATED",
            "title": "Leave Request Submitted",
            "message": "Your leave request for 3 days has been submitted",
            "isRead": false,
            "createdAt": "2025-12-05T10:30:00Z"
        }
    ],
    "pageNumber": 0,
    "pageSize": 10,
    "totalElements": 1,
    "totalPages": 1
}
```

#### 1.7 Mark Notification as Read
```
PUT /notifications/{notificationId}/read
Authorization: Bearer {token}

Response (200 OK):
{
    "id": 1,
    "isRead": true,
    "readAt": "2025-12-05T11:00:00Z"
}
```

---

### 2. Manager Endpoints

#### 2.1 Get Pending Leave Requests (Team)
```
GET /leaves/team-requests?status=PENDING&page=0&size=10
Authorization: Bearer {token}

Response (200 OK):
{
    "content": [
        {
            "id": 1,
            "requestId": "LR-2025-001",
            "employeeId": 10,
            "employeeName": "John Doe",
            "leaveType": "PERSONAL",
            "startDate": "2025-12-15",
            "endDate": "2025-12-17",
            "numberOfDays": 3,
            "reason": "Personal reasons",
            "status": "PENDING",
            "requestedOn": "2025-12-05T10:30:00Z"
        }
    ],
    "pageNumber": 0,
    "pageSize": 10,
    "totalElements": 1,
    "totalPages": 1
}
```

#### 2.2 Approve Leave Request
```
PUT /leaves/{requestId}/approve
Content-Type: application/json
Authorization: Bearer {token}

Request Body:
{
    "remarks": "Approved as per business requirement"
}

Response (200 OK):
{
    "id": 1,
    "requestId": "LR-2025-001",
    "status": "APPROVED",
    "approvedBy": 5,
    "approvedByName": "Jane Smith",
    "approvedOn": "2025-12-05T11:00:00Z",
    "message": "Leave request approved successfully"
}
```

#### 2.3 Reject Leave Request
```
PUT /leaves/{requestId}/reject
Content-Type: application/json
Authorization: Bearer {token}

Request Body:
{
    "rejectionReason": "Cannot spare resources during this period"
}

Response (200 OK):
{
    "id": 1,
    "requestId": "LR-2025-001",
    "status": "REJECTED",
    "rejectionReason": "Cannot spare resources during this period",
    "approvedBy": 5,
    "approvedOn": "2025-12-05T11:00:00Z",
    "message": "Leave request rejected successfully"
}
```

#### 2.4 Get Team Leave Balance
```
GET /leaves/team-balance?year=2025&page=0&size=10
Authorization: Bearer {token}

Response (200 OK):
{
    "content": [
        {
            "employeeId": 10,
            "employeeName": "John Doe",
            "leaveBalances": [
                {
                    "leaveTypeId": 1,
                    "leaveType": "PERSONAL",
                    "allocatedDays": 10,
                    "usedDays": 3,
                    "carriedForwardDays": 2,
                    "balanceDays": 9
                }
            ]
        }
    ],
    "pageNumber": 0,
    "pageSize": 10,
    "totalElements": 5,
    "totalPages": 1
}
```

---

### 3. Admin Endpoints

#### 3.1 Create Leave Type
```
POST /admin/leave-types
Content-Type: application/json
Authorization: Bearer {token}

Request Body:
{
    "name": "COMP_OFF",
    "description": "Compensatory Off",
    "annualAllocation": 3,
    "carryforwardLimit": 1
}

Response (201 Created):
{
    "id": 3,
    "name": "COMP_OFF",
    "description": "Compensatory Off",
    "annualAllocation": 3,
    "carryforwardLimit": 1,
    "isActive": true,
    "createdAt": "2025-12-05T10:30:00Z"
}
```

#### 3.2 Get All Leave Types
```
GET /admin/leave-types
Authorization: Bearer {token}

Response (200 OK):
{
    "leaveTypes": [
        {
            "id": 1,
            "name": "PERSONAL",
            "description": "Personal Leave",
            "annualAllocation": 10,
            "carryforwardLimit": 5,
            "isActive": true
        },
        {
            "id": 2,
            "name": "SPECIAL",
            "description": "Special Leave",
            "annualAllocation": 5,
            "carryforwardLimit": 0,
            "isActive": true
        }
    ]
}
```

#### 3.3 Allocate Annual Leaves
```
POST /admin/allocate-leaves
Content-Type: application/json
Authorization: Bearer {token}

Request Body:
{
    "year": 2025,
    "leaveAllocations": [
        {
            "employeeId": 10,
            "leaveTypeId": 1,
            "days": 10
        }
    ]
}

Response (200 OK):
{
    "message": "Leaves allocated successfully",
    "allocatedCount": 1
}
```

#### 3.4 Get All Leave Requests (System-wide)
```
GET /admin/leaves/all?status=APPROVED&page=0&size=10
Authorization: Bearer {token}

Response (200 OK):
{
    "content": [
        {
            "id": 1,
            "requestId": "LR-2025-001",
            "employeeId": 10,
            "employeeName": "John Doe",
            "managerName": "Jane Smith",
            "leaveType": "PERSONAL",
            "startDate": "2025-12-15",
            "endDate": "2025-12-17",
            "numberOfDays": 3,
            "status": "APPROVED",
            "approvedOn": "2025-12-05T11:00:00Z"
        }
    ],
    "pageNumber": 0,
    "pageSize": 10,
    "totalElements": 50,
    "totalPages": 5
}
```

#### 3.5 Carryforward Leave Balance
```
POST /admin/carryforward-balance
Content-Type: application/json
Authorization: Bearer {token}

Request Body:
{
    "fromYear": 2024,
    "toYear": 2025
}

Response (200 OK):
{
    "message": "Carryforward process completed successfully",
    "processedEmployees": 45,
    "balancesCarriedforward": 78
}
```

#### 3.6 View Audit Logs
```
GET /admin/audit-logs?leaveRequestId=1&page=0&size=10
Authorization: Bearer {token}

Response (200 OK):
{
    "content": [
        {
            "id": 1,
            "leaveRequestId": 1,
            "action": "CREATED",
            "performedBy": 10,
            "performedByName": "John Doe",
            "oldStatus": null,
            "newStatus": "PENDING",
            "remarks": null,
            "createdAt": "2025-12-05T10:30:00Z"
        }
    ],
    "pageNumber": 0,
    "pageSize": 10,
    "totalElements": 3,
    "totalPages": 1
}
```

---

## Notification Strategy

### Notification Types

1. **REQUEST_CREATED**: Employee requests a leave
   - Recipient: Manager
   - Message: "{EmployeeName} has requested {numberOfDays} days of {leaveType} leave from {startDate} to {endDate}"

2. **APPROVED**: Manager approves leave request
   - Recipient: Employee
   - Message: "Your leave request for {numberOfDays} days from {startDate} to {endDate} has been approved"

3. **REJECTED**: Manager rejects leave request
   - Recipient: Employee
   - Message: "Your leave request has been rejected. Reason: {rejectionReason}"

4. **WITHDRAWN**: Employee withdraws leave request
   - Recipient: Manager
   - Message: "{EmployeeName} has withdrawn their leave request for {numberOfDays} days"

### Notification Delivery
- Real-time notifications using Spring Reactive WebSockets (optional)
- Notifications stored in database for audit trail
- Email notifications can be sent asynchronously (future enhancement)

---

## Business Logic

### Leave Request Validation
1. Employee must have sufficient balance for the leave type
2. Leave dates must be in the future
3. Start date must be before or equal to end date
4. Maximum consecutive days based on leave type
5. No overlapping approved leaves

### Approval Workflow
1. Employee submits leave request → Status: PENDING
2. Manager reviews → Status: APPROVED or REJECTED
3. If approved, leave balance is updated
4. Audit log entry created
5. Notifications sent to all parties

### Carryforward Logic
- Run annually at fiscal year end
- For each employee and leave type:
  - Calculate unused balance from current year
  - Check carryforward limit for leave type
  - Transfer eligible balance to next year
  - Update leave balance record

### Leave Balance Calculation
```
Balance = AllocatedDays + CarriedForwardDays - UsedDays
```

### Withdrawal Rules
- Employee can withdraw only PENDING or APPROVED leaves
- Cannot withdraw past leaves
- Cannot withdraw leaves within 24 hours of start date (configurable)
- Used days are not refunded if already in progress

---

## Technology Stack

- **Framework**: Spring Boot WebFlux (Reactive)
- **Language**: Java
- **Database**: H2 (Development), can be replaced with PostgreSQL for production
- **Authentication**: JWT-based
- **API Documentation**: Springdoc OpenAPI (Swagger)
- **Validation**: Jakarta Bean Validation
- **Mapper**: MapStruct
- **Testing**: JUnit 5, Mockito, TestContainers

---

## Error Handling

### Common Error Responses

#### 400 Bad Request
```json
{
    "error": "BAD_REQUEST",
    "message": "Invalid date range",
    "timestamp": "2025-12-05T10:30:00Z"
}
```

#### 404 Not Found
```json
{
    "error": "NOT_FOUND",
    "message": "Leave request not found",
    "timestamp": "2025-12-05T10:30:00Z"
}
```

#### 403 Forbidden
```json
{
    "error": "FORBIDDEN",
    "message": "You do not have permission to perform this action",
    "timestamp": "2025-12-05T10:30:00Z"
}
```

#### 409 Conflict
```json
{
    "error": "INSUFFICIENT_BALANCE",
    "message": "Insufficient leave balance. Available: 2 days, Requested: 3 days",
    "timestamp": "2025-12-05T10:30:00Z"
}
```

---

## Future Enhancements

1. Email and SMS notifications
2. Calendar integration to block dates
3. Bulk leave approval
4. Leave policy configuration per department
5. Encashment of unused leaves
6. Half-day leave support
7. Leave counter-offer negotiation
8. Mobile application
9. Advanced analytics and reporting
10. Integration with payroll system

