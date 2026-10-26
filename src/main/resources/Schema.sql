CREATE TABLE IF NOT EXISTS wallets (
                                       wallet_id VARCHAR(32) PRIMARY KEY,
    status VARCHAR(16) NOT NULL, -- ACTIVE, FROZEN
    balance BIGINT NOT NULL,
    reserved BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_positive CHECK (balance >= 0 AND reserved >= 0 AND balance >= reserved)
    );

CREATE TABLE IF NOT EXISTS transfers (
                                         transfer_id VARCHAR(64) PRIMARY KEY,
    from_wallet VARCHAR(32) NOT NULL,
    to_wallet VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    reference VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL, -- COMPLETED, REVERSED
    reversal_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    );

CREATE TABLE IF NOT EXISTS payouts (
                                       payout_id VARCHAR(64) PRIMARY KEY,
    wallet_id VARCHAR(32) NOT NULL,
    bank_account VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL, -- PENDING, SUCCEEDED, FAILED
    bank_ref VARCHAR(64),
    conflict BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    );

CREATE TABLE IF NOT EXISTS idempotency_keys (
                                                idem_key VARCHAR(64) PRIMARY KEY,
    request_hash VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL, -- IN_PROGRESS, COMPLETED
    http_status INT,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    );