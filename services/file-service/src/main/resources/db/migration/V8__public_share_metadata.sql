CREATE TABLE file_public_shares (
    file_id uuid PRIMARY KEY REFERENCES files(id),
    title varchar(120) NOT NULL DEFAULT '',
    description varchar(300) NOT NULL DEFAULT '',
    show_thumbnail boolean NOT NULL DEFAULT true,
    revision bigint NOT NULL DEFAULT 0
);
