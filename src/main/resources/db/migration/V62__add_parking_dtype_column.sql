-- Required by @Inheritance(strategy = SINGLE_TABLE) @DiscriminatorColumn on Parking.
-- Existing rows default to the root discriminator value "Parking".
ALTER TABLE parking ADD COLUMN IF NOT EXISTS dtype VARCHAR(31) NOT NULL DEFAULT 'Parking';
