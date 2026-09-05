-- Reproduces the two-role split from infra/postgres-init in the test database.
--
-- Without this, tests would run as the schema owner and every row-level
-- security policy would be bypassed -- the tests would pass while production
-- leaked. The security boundary has to be the same one the tests exercise.
create role almira_app login password 'app_dev_password';
grant connect on database almira to almira_app;
grant usage on schema public to almira_app;
