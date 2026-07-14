-- A contact can legitimately belong to many uploads: the same phone number
-- reappears in a later policy file. The old model stored a single upload_id on
-- the contact row, so ExcelUploadService.refreshContactRow() repointed that
-- column to the newest upload every time an existing phone was re-seen —
-- silently stealing the contact from every earlier campaign/reminder that
-- referenced the previous upload. Their recipient list, table count, and even
-- the actual dispatch then resolved to zero. This link table makes the
-- relationship many-to-many so every upload keeps its recipients forever.
CREATE TABLE contact_upload_link (
    upload_id  UUID NOT NULL REFERENCES contact_upload(id) ON DELETE CASCADE,
    contact_id UUID NOT NULL REFERENCES contact(id)        ON DELETE CASCADE,
    PRIMARY KEY (upload_id, contact_id)
);

CREATE INDEX idx_contact_upload_link_upload  ON contact_upload_link(upload_id);
CREATE INDEX idx_contact_upload_link_contact ON contact_upload_link(contact_id);

-- Backfill the links every contact already carries via its single upload_id so
-- existing campaigns keep resolving their recipients after this change.
INSERT INTO contact_upload_link (upload_id, contact_id)
SELECT upload_id, id FROM contact WHERE upload_id IS NOT NULL
ON CONFLICT DO NOTHING;
