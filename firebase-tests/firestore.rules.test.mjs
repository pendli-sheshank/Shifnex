// Security-rules unit tests for Schedulo2.
//
// Verifies the multi-tenant isolation guarantees added to firestore.rules:
//   * per-user collections stay private (jobs cross-user read is denied)
//   * team collections are readable only by team members
//   * self-join requires the correct invite code + deterministic member id
//   * field lockdown (hasOnly) rejects unknown fields on membership docs
//
// Run with: npm test   (starts the Firestore emulator, then mocha)

import { readFileSync } from 'node:fs';
import { strict as assert } from 'node:assert';
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from '@firebase/rules-unit-testing';
import {
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  writeBatch,
  increment,
  collection,
  query,
  where,
  getDocs,
} from 'firebase/firestore';

const PROJECT_ID = 'schedulo2-test';
const OWNER = 'owner_uid';
const MEMBER = 'member_uid';
const OUTSIDER = 'outsider_uid';
const TEAM = 'team1';
const CODE = 'ABC123';

let testEnv;

// Deterministic membership id used throughout the rules.
const memberId = (teamId, uid) => `${teamId}_${uid}`;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  // Seed a team with an owner (manager) and one member, bypassing rules.
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'teams', TEAM), {
      name: 'Cafe',
      ownerId: OWNER,
      inviteCode: CODE,
      createdAt: 1,
      memberCount: 2,
    });
    await setDoc(doc(db, 'invite_codes', CODE), { teamId: TEAM });
    await setDoc(doc(db, 'team_members', memberId(TEAM, OWNER)), {
      teamId: TEAM, userId: OWNER, role: 'manager', joinedAt: 1,
      displayName: 'Owner', email: 'o@x.com', defaultHourlyRate: 0,
    });
    await setDoc(doc(db, 'team_members', memberId(TEAM, MEMBER)), {
      teamId: TEAM, userId: MEMBER, role: 'member', joinedAt: 1,
      displayName: 'Member', email: 'm@x.com',
    });
    await setDoc(doc(db, 'team_messages', 'msg1'), {
      teamId: TEAM, senderId: MEMBER, senderName: 'Member', text: 'hi',
      isAnnouncement: false, isPinned: false, createdAt: 1,
    });
    // A job owned by MEMBER — used for the cross-user read test.
    await setDoc(doc(db, 'jobs', 'job1'), {
      userId: MEMBER, title: 'Barista', defaultHourlyRate: 15, goalHours: 40,
    });
  });
});

const ctxFor = (uid) => testEnv.authenticatedContext(uid).firestore();

describe('jobs are private to their owner', () => {
  it('owner of the job can read it', async () => {
    await assertSucceeds(getDoc(doc(ctxFor(MEMBER), 'jobs', 'job1')));
  });
  it('another authenticated user cannot read it', async () => {
    await assertFails(getDoc(doc(ctxFor(OUTSIDER), 'jobs', 'job1')));
  });
});

describe('team data is readable only by members', () => {
  it('a member can read the team doc', async () => {
    await assertSucceeds(getDoc(doc(ctxFor(MEMBER), 'teams', TEAM)));
  });
  it('an outsider cannot read the team doc', async () => {
    await assertFails(getDoc(doc(ctxFor(OUTSIDER), 'teams', TEAM)));
  });
  it('a member can query the team roster', async () => {
    const q = query(collection(ctxFor(MEMBER), 'team_members'), where('teamId', '==', TEAM));
    await assertSucceeds(getDocs(q));
  });
  it('an outsider cannot query the team roster', async () => {
    const q = query(collection(ctxFor(OUTSIDER), 'team_members'), where('teamId', '==', TEAM));
    await assertFails(getDocs(q));
  });
  it('an outsider cannot read team messages', async () => {
    const q = query(collection(ctxFor(OUTSIDER), 'team_messages'), where('teamId', '==', TEAM));
    await assertFails(getDocs(q));
  });
  it('a user can get their own (not-yet-existing) membership doc for the pre-join check', async () => {
    await assertSucceeds(getDoc(doc(ctxFor(OUTSIDER), 'team_members', memberId(TEAM, OUTSIDER))));
  });
});

describe('invite-code lookup', () => {
  it('any signed-in user can GET an invite code by its exact id', async () => {
    await assertSucceeds(getDoc(doc(ctxFor(OUTSIDER), 'invite_codes', CODE)));
  });
  it('invite codes cannot be listed/queried', async () => {
    await assertFails(getDocs(collection(ctxFor(OUTSIDER), 'invite_codes')));
  });
});

describe('self-join requires the correct invite code', () => {
  it('joining with the correct code and deterministic id succeeds', async () => {
    const db = ctxFor(OUTSIDER);
    await assertSucceeds(setDoc(doc(db, 'team_members', memberId(TEAM, OUTSIDER)), {
      teamId: TEAM, userId: OUTSIDER, role: 'member', joinedAt: 2,
      displayName: 'New', email: 'n@x.com', inviteCode: CODE,
    }));
  });
  it('joining with a WRONG code is denied', async () => {
    const db = ctxFor(OUTSIDER);
    await assertFails(setDoc(doc(db, 'team_members', memberId(TEAM, OUTSIDER)), {
      teamId: TEAM, userId: OUTSIDER, role: 'member', joinedAt: 2,
      displayName: 'New', email: 'n@x.com', inviteCode: 'WRONG!',
    }));
  });
  it('joining without any code is denied', async () => {
    const db = ctxFor(OUTSIDER);
    await assertFails(setDoc(doc(db, 'team_members', memberId(TEAM, OUTSIDER)), {
      teamId: TEAM, userId: OUTSIDER, role: 'member', joinedAt: 2,
      displayName: 'New', email: 'n@x.com',
    }));
  });
  it('a non-deterministic membership id is denied', async () => {
    const db = ctxFor(OUTSIDER);
    await assertFails(setDoc(doc(db, 'team_members', 'random_id'), {
      teamId: TEAM, userId: OUTSIDER, role: 'member', joinedAt: 2,
      displayName: 'New', email: 'n@x.com', inviteCode: CODE,
    }));
  });
  it('self-promoting to manager without owning the team is denied', async () => {
    const db = ctxFor(OUTSIDER);
    await assertFails(setDoc(doc(db, 'team_members', memberId(TEAM, OUTSIDER)), {
      teamId: TEAM, userId: OUTSIDER, role: 'manager', joinedAt: 2,
      displayName: 'New', email: 'n@x.com', inviteCode: CODE,
    }));
  });
});

describe('join batch (membership write + memberCount increment)', () => {
  it('a joiner may write their membership and bump memberCount atomically', async () => {
    const db = ctxFor(OUTSIDER);
    const batch = writeBatch(db);
    batch.set(doc(db, 'team_members', memberId(TEAM, OUTSIDER)), {
      teamId: TEAM, userId: OUTSIDER, role: 'member', joinedAt: 2,
      displayName: 'New', email: 'n@x.com', inviteCode: CODE,
    });
    batch.update(doc(db, 'teams', TEAM), { memberCount: increment(1) });
    await assertSucceeds(batch.commit());
  });
  it('a non-owner cannot change other team fields', async () => {
    await assertFails(updateDoc(doc(ctxFor(OUTSIDER), 'teams', TEAM), { name: 'Hacked' }));
  });
});

describe('field lockdown', () => {
  it('an unknown field on a membership doc is rejected', async () => {
    const db = ctxFor(OUTSIDER);
    await assertFails(setDoc(doc(db, 'team_members', memberId(TEAM, OUTSIDER)), {
      teamId: TEAM, userId: OUTSIDER, role: 'member', joinedAt: 2,
      displayName: 'New', email: 'n@x.com', inviteCode: CODE,
      isSuperAdmin: true,
    }));
  });
});

describe('schedule-assignment notifications', () => {
  const SHIFT = 'tshift1';
  const notification = (overrides = {}) => ({
    userId: MEMBER,
    type: 'shift_assigned',
    teamId: TEAM,
    teamShiftId: SHIFT,
    teamName: 'Cafe',
    company: 'Cafe',
    startTime: 100,
    endTime: 200,
    createdAt: 100,
    read: false,
    ...overrides,
  });

  beforeEach(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'team_shifts', SHIFT), {
        teamId: TEAM, assignedTo: MEMBER, assignedBy: OWNER,
        company: 'Cafe', role: '', startTime: 100, endTime: 200,
        hourlyRate: 15, notes: '', status: 'accepted', tasks: [],
      });
      await setDoc(doc(db, 'notifications', 'n1'), notification());
    });
  });

  it('the shift assigner can create a notification for the assignee', async () => {
    await assertSucceeds(setDoc(doc(ctxFor(OWNER), 'notifications', 'n2'), notification()));
  });
  it('a random user cannot create a notification (not the assigner)', async () => {
    await assertFails(setDoc(doc(ctxFor(OUTSIDER), 'notifications', 'n2'), notification()));
  });
  it('the assigner cannot address the notification to someone other than the assignee', async () => {
    await assertFails(setDoc(doc(ctxFor(OWNER), 'notifications', 'n2'), notification({ userId: OUTSIDER })));
  });
  it('the recipient can query their own notifications', async () => {
    const q = query(collection(ctxFor(MEMBER), 'notifications'), where('userId', '==', MEMBER));
    await assertSucceeds(getDocs(q));
  });
  it('another user cannot read someone else\'s notification', async () => {
    await assertFails(getDoc(doc(ctxFor(OUTSIDER), 'notifications', 'n1')));
  });
  it('the recipient can mark it read', async () => {
    await assertSucceeds(updateDoc(doc(ctxFor(MEMBER), 'notifications', 'n1'), { read: true }));
  });
  it('the recipient cannot rewrite other fields', async () => {
    await assertFails(updateDoc(doc(ctxFor(MEMBER), 'notifications', 'n1'), { company: 'Other' }));
  });
});

describe('job pay-cycle fields', () => {
  const jobDoc = (overrides = {}) => ({
    userId: MEMBER,
    title: 'Cafe',
    defaultHourlyRate: 15,
    goalHours: 40,
    weeklyCycleStartDay: 'Friday',
    ...overrides,
  });

  it('a job with no pay-cycle fields is still accepted', async () => {
    // Older app versions don't send them, and both clients write whole documents.
    await assertSucceeds(setDoc(doc(ctxFor(MEMBER), 'jobs', 'j2'), jobDoc()));
  });
  it('the three supported frequencies are accepted', async () => {
    for (const [i, freq] of ['WEEKLY', 'BIWEEKLY', 'MONTHLY'].entries()) {
      await assertSucceeds(
        setDoc(doc(ctxFor(MEMBER), 'jobs', `jf${i}`), jobDoc({ payFrequency: freq }))
      );
    }
  });
  it('an unknown frequency is rejected', async () => {
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'jobs', 'j2'), jobDoc({ payFrequency: 'FORTNIGHTLY' })));
  });
  it('a lowercase frequency is rejected', async () => {
    // The clients uppercase before writing; accepting both spellings would let two
    // representations of the same cycle into the data.
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'jobs', 'j2'), jobDoc({ payFrequency: 'weekly' })));
  });
  it('a biweekly anchor is accepted and a malformed one is not', async () => {
    await assertSucceeds(setDoc(doc(ctxFor(MEMBER), 'jobs', 'j2'),
      jobDoc({ payFrequency: 'BIWEEKLY', payCycleAnchorMillis: 1751515200000 })));
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'jobs', 'j3'),
      jobDoc({ payFrequency: 'BIWEEKLY', payCycleAnchorMillis: 'last friday' })));
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'jobs', 'j4'),
      jobDoc({ payFrequency: 'BIWEEKLY', payCycleAnchorMillis: -1 })));
  });
  it('another user still cannot create a job for someone else', async () => {
    await assertFails(setDoc(doc(ctxFor(OUTSIDER), 'jobs', 'j2'), jobDoc()));
  });
});

describe('feedback submissions', () => {
  const report = (overrides = {}) => ({
    id: 'f2',
    userId: MEMBER,
    userEmail: 'm@x.com',
    category: 'bug',
    description: 'The pay screen shows last week total.',
    stepsToReproduce: '',
    screenshotUrl: '',
    appVersion: '91.0',
    platform: 'android',
    osVersion: '14',
    deviceModel: 'Pixel 8',
    status: 'new',
    createdAt: 100,
    ...overrides,
  });

  beforeEach(async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'feedback', 'f1'), report({ id: 'f1' }));
    });
  });

  it('a signed-in user can file their own report', async () => {
    await assertSucceeds(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), report()));
  });
  it('feature and other are also accepted categories', async () => {
    await assertSucceeds(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), report({ category: 'feature' })));
    await assertSucceeds(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f3'), report({ id: 'f3', category: 'other' })));
  });
  it('a report cannot be attributed to another user', async () => {
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), report({ userId: OUTSIDER })));
  });
  it('an unknown category is rejected', async () => {
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), report({ category: 'crash' })));
  });
  it('an empty description is rejected', async () => {
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), report({ description: '' })));
  });
  it('a whitespace-only description is rejected', async () => {
    // The clients trim before sending; without trim() in the rules a report of
    // pure spaces would clear the length floor and land as an unactionable doc.
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), report({ description: '    \n\t  ' })));
  });
  it('the document id must match the id in the payload', async () => {
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f9'), report({ id: 'f2' })));
  });
  it('a report cannot be filed already triaged', async () => {
    // update is denied, so a status set at creation would be permanent.
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), report({ status: 'triaged' })));
  });
  it('unknown fields are rejected', async () => {
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), report({ isAdmin: true })));
  });
  it('a description at the 2000-char cap is accepted but one over is not', async () => {
    // The cap is mirrored in FeedbackLimits.MAX_DESCRIPTION; if the two drift,
    // the client lets the user type a report the server then refuses.
    await assertSucceeds(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), report({ description: 'x'.repeat(2000) })));
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f3'), report({ id: 'f3', description: 'x'.repeat(2001) })));
  });
  it('over-long steps to reproduce are rejected', async () => {
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), report({ stepsToReproduce: 'x'.repeat(2001) })));
  });
  it('a missing createdAt is rejected', async () => {
    const { createdAt, ...withoutTimestamp } = report();
    await assertFails(setDoc(doc(ctxFor(MEMBER), 'feedback', 'f2'), withoutTimestamp));
  });
  it('the reporter can read their own report back', async () => {
    await assertSucceeds(getDoc(doc(ctxFor(MEMBER), 'feedback', 'f1')));
  });
  it('another user cannot read someone else\'s report', async () => {
    await assertFails(getDoc(doc(ctxFor(OUTSIDER), 'feedback', 'f1')));
  });
  it('a submitted report is immutable — the reporter cannot edit it', async () => {
    await assertFails(updateDoc(doc(ctxFor(MEMBER), 'feedback', 'f1'), { description: 'changed' }));
  });
  it('a submitted report cannot be withdrawn', async () => {
    await assertFails(deleteDoc(doc(ctxFor(MEMBER), 'feedback', 'f1')));
  });
});
