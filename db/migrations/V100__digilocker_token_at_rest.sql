-- =============================================================================
-- V100 · A DigiLocker session is kept the way V24 said it would be.
-- Refs: docs/known-issues.md §10, docs/providers/digilocker.md, V24
--
-- Until now ConnectService wrote the DigiLocker session token, in plain text,
-- into provider_connections.external_ref — the column V24 documents as "never a
-- credential" — and left access_token_enc and expires_at empty. The service now
-- writes the token encrypted with the household's data key, and its expiry.
--
-- What is already stored can only have come from the in-process sandbox (live
-- refuses to start), so it is nobody's credential; it is still a token-shaped
-- string in a column every member can read. It is removed rather than
-- encrypted here, because a migration has no data key: the connection is marked
-- expired, and connecting again takes a minute. Account Aggregator and WhatsApp
-- keep their external_ref, which holds a consent handle or a number, as V24
-- intended.
-- =============================================================================

update provider_connections
   set external_ref = null,
       status = case when status = 'active' then 'expired' else status end
 where provider = 'digilocker'
   and external_ref is not null;

comment on column provider_connections.external_ref is
  'The provider''s own handle for this link: a consent id or a number. Never a credential; '
  'a DigiLocker session is in access_token_enc (V100).';
