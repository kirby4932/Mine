# Discord Stats Bot

<img src="./assets/Mine.png" alt="Bot Profile Picture" width="120" height="120">


## Overview

This Java-based bot uses the Discord4J library to display user activity statistics directly inside Discord via slash commands and interactive buttons.

**Note:** This bot does not log any data on its own. It depends entirely on a separate logging bot called **Sugu**, which collects and stores activity data in a MariaDB database. This bot reads and visualizes that data inside Discord.

---

## Features

- **Slash Commands**
  - `/stats [user] [dayspan]`: Show activity statistics for a user
  - `/leaderboard [game] [dayspan]`: Leaderboard of top users for a given game
  - `/spotify [user] [dayspan]`: Show top songs and artists for a user

- **Button Interactions**
  - Interactive `dayspan` buttons allow quick timespan switching (e.g., 7, 14, 30 days)
  - Button IDs follow pattern: `command_dayspan_N` (e.g., `stats_dayspan_7`)

- **Statistical Analysis**
  - Uses MariaDB and SQL queries to track user activity
  - Supports detailed leaderboard rankings and personal summaries

---

## Architecture

| Component        | Purpose                             |
|------------------|--------------------------------------|
| Java             | Core logic                           |
| Discord4J        | Discord API binding                  |
| MariaDB          | Source of stored activity data       |
| Docker           | Containerization and deployment      |

---

## Dependency

This bot depends on the [Sugu Bot](https://github.com/tekoWeMa/sugu), which must be running and logging activity data to the same MariaDB database.

**Sugu** handles:

- Tracking presence states (online, idle, do-not-disturb)
- Recording game sessions with durations
- Logging Spotify activity (song, artist, listening time)
- Persisting activity logs to a relational schema

This bot connects to that same schema and renders the data in a user-friendly way through Discord.

---

## Docker Compose

```yaml
version: '3.8'

services:
  bot:
    build: .
    environment:
      - DISCORD_CLIENT_TOKEN_MINE
      - DB_HOST_MINE
      - DB_USERNAME_MINE
      - DB_PASSWORD_MINE
      - BOT_OWNER_ID
    restart: unless-stopped
```

---

## Database Indexes

The schema is owned by Sugu. The list below is the verified live state of the `sugu`
database, not a wishlist -- it was dumped from `information_schema.statistics` on
2026-09-14.

### Activity Table Indexes

```sql
-- Foreign key indexes (created by Sugu)
CREATE INDEX auto_user_id       ON Activity (auto_user_id);
CREATE INDEX auto_status_id     ON Activity (auto_status_id);
CREATE INDEX auto_app_state_id  ON Activity (auto_app_state_id);

-- /spotify and Spotify leaderboards (listening activities)
CREATE INDEX idx_activity_listening_lookup ON Activity (
    auto_type_id,
    auto_app_id,
    auto_user_id,
    starttime,
    endtime,
    auto_app_state_id
);

-- /stats and game leaderboards (playing activities)
CREATE INDEX idx_activity_playing_lookup ON Activity (
    auto_type_id,
    auto_user_id,
    auto_app_id,
    starttime
);

-- Unfiltered total-hours aggregations (no type filter).
-- Also serves the auto_app_id foreign key as its leading column, which is why
-- no standalone auto_app_id index exists.
CREATE INDEX idx_activity_app_user ON Activity (auto_app_id, auto_user_id);
```

### Lookup Table Indexes

```sql
CREATE INDEX idx_user_username     ON User (username(768));
CREATE INDEX idx_type_type         ON Type (type(768));
CREATE INDEX idx_application_name  ON Application (name(768));
CREATE INDEX idx_appstate_lookup   ON AppState (state(128), details(128));
```

`User.user_id` and `Application.app_id` additionally carry unique indexes created by Sugu.

### Measured Query Cost

Median of three runs, `SQL_NO_CACHE`, measured 2026-09-14 against 8.27M Activity rows:

| Command | Dayspan | Duration |
| --- | --- | --- |
| `/stats` (single user) | 7 | 0.33 s |
| `/stats` (single user) | 365 | 1.45 s |
| `/leaderboard` (global) | 7 | 5.08 s |
| `/leaderboard` (global) | 365 | 15.76 s |
| `/leaderboard spotify` (global) | 365 | 7.11 s |

The global leaderboards are slow because they aggregate every user's rows:
1,240,580 `playing` rows fall inside a 365-day window, out of 3,821,419 total.

### Why There Is No `(auto_type_id, starttime, ...)` Index

A covering index `(auto_type_id, starttime, auto_user_id, auto_app_id, endtime)` was
built and benchmarked on 2026-09-14 to speed up the global leaderboards. **It was
dropped again -- it made every query slower.**

| Query | Before | With index |
| --- | --- | --- |
| `/stats` 365 | 1.45 s | 2.19 s |
| `/leaderboard` global 365 | 15.76 s | 19.15 s |
| `/leaderboard spotify` 365 | 7.11 s | 7.58 s |

Two reasons, both worth knowing before trying again:

1. **The range scan never happens.** The queries join `Type` to resolve
   `type = 'playing'` into `auto_type_id`, so that column is a join reference, not a
   constant. MariaDB can `ref` on it but cannot then range-scan `starttime` in the
   next index position. `EXPLAIN` confirmed `key_len: 5` -- only `auto_type_id` was
   used. Rewriting the predicate as a literal `auto_type_id = 1` did not help either
   (16.2 s vs 15.8 s).

2. **The buffer pool is the real constraint.** `innodb_buffer_pool_size` is 256 MB
   against roughly 2.1 GB of Activity data plus indexes, on a host with 15 GB RAM.
   `Innodb_buffer_pool_pages_free` sits at 1 of 16128. Every query is disk-bound, so
   adding 280 MB of index only increased cache pressure.

Raising `innodb_buffer_pool_size` is the change that would actually move these
numbers. It is a MariaDB container setting and affects Sugu as well, so it is not
made here.

### Viewing Existing Indexes

To check which indexes currently exist:

```sql
-- Show all indexes for Activity table
SHOW INDEX FROM Activity;

-- Show indexes for lookup tables
SHOW INDEX FROM User;
SHOW INDEX FROM Type;
SHOW INDEX FROM Application;
SHOW INDEX FROM AppState;
```