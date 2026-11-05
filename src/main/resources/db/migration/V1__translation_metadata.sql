CREATE TABLE quota_account (
    account_id varchar(64) PRIMARY KEY
);

CREATE TABLE translation_request (
    request_id uuid PRIMARY KEY,
    account_id varchar(64) NOT NULL REFERENCES quota_account(account_id),
    model_alias varchar(100) NOT NULL,
    provider varchar(100) NOT NULL,
    provider_model varchar(200) NOT NULL,
    prompt_version varchar(30) NOT NULL,
    source_language varchar(20) NOT NULL,
    target_language varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    lease_expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    status varchar(30) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','EXPIRED')),
    charged_tokens bigint NOT NULL CHECK (charged_tokens >= 0),
    input_tokens bigint,
    output_tokens bigint,
    error_code varchar(60)
);
CREATE INDEX translation_request_account_created ON translation_request(account_id, created_at);
CREATE INDEX translation_request_running ON translation_request(account_id, lease_expires_at) WHERE status = 'RUNNING';
