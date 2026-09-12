# Your subdivision work record — a short guide

KaavalanNote 2.6.0 adds a private record of your subdivision: the stations you are
responsible for, the staff posted to them, the matters you are following, and your own
dated reviews.

Three things are worth saying before anything else.

- **This is your notebook, not a system.** Staff do not log in, do not get accounts, and
  are never sent anything. Nothing here notifies anyone.
- **It stays on your phone.** The record is encrypted locally. There is no server, no
  shared database and no one else's copy.
- **Nothing changes about how you already work.** Today, Instructions and Contacts are
  still the three tabs. The note bar still saves a note in a few seconds. You can ignore
  everything in this guide and the app works exactly as it did.

---

## 1. Set up your subdivision

Open **Today**. Below the top bar there is a row called **Subdivision review**; before
setup it reads *Set up your subdivision*.

Tap it, then **Set up subdivision**. Only the name is required. District and your own
display name are optional, and all three can be changed later.

That is the whole setup. There is no roster to build first.

## 2. Stations and units

**Contacts → Stations & staff → Stations.**

If you already had station names typed into your contacts, the upgrade has turned them
into real stations for you. Nothing was invented: no subdivision was created, and nobody
was classified as staff.

**Add station / unit** takes a name, a type (it says `Station`, change it to `Outpost`,
`Unit`, `Wing` or whatever you use) and free notes. Each station row shows how many active
staff and how much open work it carries.

Station names must be unique. If you try to add one that already exists as an archived
station, the app tells you to reopen that station rather than quietly making a second one
with the same name.

**Archive** is how a station goes away — never a delete. If a station still has active
staff, active matters or open instructions, the archive is refused and the message names
the specific thing to deal with first. Archived stations keep their history and can be
reopened.

## 3. Turning a contact into staff

Your contacts stay ordinary contacts until you say otherwise. Importing someone from the
phone does not make them staff.

**Contacts → Stations & staff → Staff → Add from contacts.** Pick the person, mark them as
staff, choose their station, and write their responsibilities in plain words —
*"coastal beat, night patrol"* — as many lines as you want.

Their name, rank and phone number are untouched. **Edit contact details** opens the same
contact editor you already use.

### Transfers

Change the station on a staff member and the app records a dated posting entry: from
where, to where, and what their responsibilities were at the time.

**Moving an officer does not move their old work.** An instruction remembers the station
it was actually recorded at. If a sub-inspector who handled a case at one station
transfers to another, last month's work still reads as having happened where it happened.
That is deliberate — a transfer should not rewrite history. If you genuinely want an
instruction to move, change its work context explicitly (section 5).

Deactivating a posting keeps the person, their responsibilities and their full history.
Inactive staff stay searchable and their linked work stays reachable. Their instructions
are not reassigned behind your back.

## 4. Matters

**Instructions → Matters.** A matter groups related instructions and their history — an
inquiry, a recurring problem, a petition you are tracking.

**Add matter** takes a title, an optional station (or subdivision-wide, if it spans
several), an optional reference number and free context.

A matter's detail screen shows its context and every instruction filed under it, with the
usual open/closed filters.

Two ways to put work into a matter:

- **Add instruction** opens the normal capture sheet with the matter already selected and
  visibly labelled. You type the note the way you always do and it is filed on save. If
  the save is refused, nothing is half-saved.
- **Link instruction** attaches an instruction you already recorded, showing you what
  context will change before you confirm.

If you have a note half-written and then open capture from a matter, the app does not
throw your draft away — it asks.

## 5. Work context on an instruction

Open any instruction. Under the text there is a **Station & matter** row, and a
**Change work context** action.

That row shows the station recorded *on the instruction*, which is not necessarily the
station the assigned contact is posted to today. Changing the context writes a dated entry
into the instruction's journal naming both the old and the new, so the history reads
honestly later.

Reassigning an instruction to a different contact changes who is responsible. It does not
change the station or the matter. Those move only when you move them.

Search now finds the station and the matter title or reference as well as the note text,
the contact and the journal.

## 6. Reviews

**Today → Subdivision review.**

Choose a scope — the whole subdivision, one station, or one officer — and you get a plain
count of open work and work that is ready to verify, then the list itself.

Five filters:

- **Open** — everything still live. Reported-done work stays here until you verify it.
- **Ready to verify** — someone told you it is finished; you have not confirmed it.
- **Deadline passed** — open work whose deadline is behind you. No red badges, no shame.
- **No update in 7 days** — measured from the last update you actually recorded, or from
  when the instruction was created if there has never been one.
- **Changed since last review** — compared against your previous review of this scope. If
  there is no earlier review it says so rather than inventing a date.

Tap any instruction to open the detail you already know: add an update, reassign, set a
reminder, verify.

**Record review** saves a dated note against the scope, with the counts as they were at
the moment you saved. Those counts are labelled **At this review** so they are never
mistaken for today's numbers. Previous reviews are listed below with their notes.

Recording a review does not complete anything, does not message anyone and does not change
a single instruction. It is your note about a point in time.

## 7. Private workspace

Everything described here is normal-workspace only. In the private workspace the three
entry rows are hidden, and hidden or sensitive records never appear in any subdivision
list, count, review, picker or search result.

## 8. Backups

The subdivision record is part of your backup, not an extra you have to remember.

- **Encrypted full backup** carries everything — profile, stations, staff postings,
  responsibilities, matters, reviews, and the station and matter recorded on every
  instruction.
- **Manual backup** is now format 4. Backups you made with older versions still restore;
  where a station name can be recovered from an old record it becomes a real station, and
  no profile or staff classification is ever invented for you.
- **JSON and CSV export** carry the same information, including a labelled subdivision
  block. Notes with commas, quotes, Tamil text or several paragraphs survive intact.

A restore either succeeds completely or changes nothing. It will not import half a file and
leave you guessing which half.

Your backup is your data. If you share the app with a colleague, share the app — never a
backup file.

## 9. Giving the app to a friend

Send them the signed APK or the release link. That is all.

They set up their own subdivision, add their own stations and import their own contacts.
There is no shared account, no common database, nothing to join and no data of yours in
their copy. Two officers running this app have nothing in common but the software.
