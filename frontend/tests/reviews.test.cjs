const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../js/app.js'), 'utf8');

function element(initial = {}) {
  return {
    hidden: false, textContent: '', innerHTML: '', value: '', dataset: {}, disabled: false,
    querySelectorAll: () => [], querySelector: () => null,
    addEventListener() {}, setAttribute() {},
    classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
    ...initial
  };
}

function page(elements = {}, fetchImpl = async () => { throw new Error('unexpected request'); }) {
  const context = vm.createContext({
    fetch: fetchImpl,
    URLSearchParams,
    window: { location: { hostname: 'localhost', search: '', href: '' } },
    document: {
      body: { dataset: { page: '' } },
      getElementById: id => elements[id] ?? null,
      querySelector: () => null,
      querySelectorAll: () => [],
      addEventListener() {}
    }
  });
  vm.runInContext(source, context);
  return { context, run: code => vm.runInContext(code, context) };
}

test('assignment suggestions put staff without unfinished work first, then use weighted ratings', () => {
  const p = page();
  p.context.candidates = [
    { id: 1, name: 'Busy Five', openAssignmentCount: 2, averageRating: 5, ratingCount: 50, rankingScore: 4.9 },
    { id: 2, name: 'One Five', openAssignmentCount: 0, averageRating: 5, ratingCount: 1 },
    { id: 3, name: 'Many Four', openAssignmentCount: 0, averageRating: 4, ratingCount: 20 },
    { id: 4, name: 'Unrated', openAssignmentCount: 0, averageRating: null, ratingCount: 0 }
  ];
  p.run('availableTechnicians = candidates');
  const ids = p.run('sortedTechnicianCandidates().map(item => item.id)');
  assert.deepEqual(Array.from(ids), [3, 2, 4, 1]);
  const html = p.run("renderTechnicianCandidates('detail-technician')");
  assert.match(html, /No unfinished assignments/);
  assert.match(html, /2 unfinished assignments/);
  assert.match(html, /No ratings yet/);
  assert.match(html, /review-admin\.html\?technicianId=3/);
});

test('only the student owner of a completed request sees rating controls', () => {
  const p = page();
  p.context.student = { id: 10, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
  p.context.technician = { id: 22, roles: ['MAINTENANCE_STAFF'], activeRole: 'MAINTENANCE_STAFF' };
  p.context.ticket = { id: 7, submittedById: 10, assignedTechnicianId: 22,
    assignedTechnicianName: '<script>alert(1)</script>', status: 'COMPLETED' };
  const owner = p.run('renderDetailActions(ticket, student)');
  assert.match(owner, /ticket-review-form/);
  assert.match(owner, /reviews\.html\?technicianId=22/);
  assert.doesNotMatch(owner, /<script>/);
  assert.doesNotMatch(p.run('renderDetailActions(ticket, technician)'), /ticket-review-form/);
  p.run("ticket.status = 'CLOSED'");
  assert.match(p.run('renderDetailActions(ticket, student)'), /ticket-review-form/);
  p.run("ticket.status = 'IN_PROGRESS'");
  assert.doesNotMatch(p.run('renderDetailActions(ticket, student)'), /ticket-review-form/);
  p.run("ticket.status = 'COMPLETED'; ticket.assignedTechnicianId = 10");
  const ownRepair = p.run('renderDetailActions(ticket, student)');
  assert.doesNotMatch(ownRepair, /ticket-review-form/);
  assert.match(ownRepair, /cannot rate a repair you completed yourself/i);
});

test('rating submission sends score and optional text once and validates the star range', async () => {
  const requests = [];
  const elements = {
    'ticket-review-score': element({ value: '5' }),
    'ticket-review-comment': element({ value: '  Great repair  ' }),
    'ticket-review-message': element(),
    'ticket-review-save': element()
  };
  const p = page(elements, async (url, options) => {
    requests.push({ url, options });
    return { ok: true, status: 200, json: async () => ({ score: 5 }) };
  });
  p.context.student = { id: 10, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
  p.run('currentWorkspaceUser = student');
  await p.run('saveTicketReview({preventDefault() {}}, 7)');
  assert.match(requests[0].url, /reviews\/tickets\/7\/mine$/);
  assert.equal(requests[0].options.headers['X-Active-Role'], 'GENERAL_USER');
  assert.deepEqual(JSON.parse(requests[0].options.body), { score: 5, comment: 'Great repair' });
  assert.match(elements['ticket-review-message'].textContent, /saved/);
  elements['ticket-review-score'].value = '0';
  await p.run('saveTicketReview({preventDefault() {}}, 7)');
  assert.equal(requests.length, 1);
});

test('editing a moderated rating explains that its comment remains hidden', async () => {
  const calls = [];
  const score = element({ value: '4' });
  const comment = element({ value: 'Updated comment' });
  const message = element();
  const p = page({
    'ticket-review-form': element({ querySelectorAll: () => [score, comment] }),
    'ticket-review-score': score,
    'ticket-review-comment': comment,
    'ticket-review-save': element(),
    'ticket-review-message': message
  }, async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith('/reviews/me/ban')) {
      return { ok: true, status: 200, json: async () => ({ banned: false }) };
    }
    return { ok: true, status: 200, json: async () => ({
      score: 4, comment: 'Updated comment', visibility: 'TEXT_REMOVED'
    }) };
  });
  p.context.student = { id: 10, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
  p.run('currentWorkspaceUser = student');

  await p.run('loadTicketReview(7)');
  assert.match(message.textContent, /star rating is visible.*comment is hidden.*administrator/i);
  score.value = '5';
  comment.value = 'Revised comment';
  await p.run('saveTicketReview({ preventDefault() {} }, 7)');

  const saved = calls.find((call) => call.options.method === 'PUT');
  assert.deepEqual(JSON.parse(saved.options.body), { score: 5, comment: 'Revised comment' });
  assert.match(message.textContent, /star rating was updated.*comment remains hidden.*administrator/i);
  assert.doesNotMatch(message.textContent, /public|can be edited at any time/i);
});

test('student rating controls follow the current suspension state', async () => {
  const score = element();
  const comment = element();
  const save = element();
  const form = element({ querySelectorAll: () => [score, comment, save] });
  const message = element();
  let suspended = true;
  const p = page({
    'ticket-review-form': form,
    'ticket-review-score': score,
    'ticket-review-comment': comment,
    'ticket-review-save': save,
    'ticket-review-message': message
  }, async (url) => url.endsWith('/reviews/me/ban')
    ? { ok: true, status: 200, json: async () => ({ banned: suspended, reason: 'Repeated abuse' }) }
    : { ok: false, status: 404 });

  await p.run('loadTicketReview(7)');
  assert.equal(score.disabled, true);
  assert.equal(comment.disabled, true);
  assert.equal(save.disabled, true);
  assert.match(message.textContent, /suspended.*Repeated abuse/i);

  suspended = false;
  await p.run('loadTicketReview(7)');
  assert.equal(score.disabled, false);
  assert.equal(comment.disabled, false);
  assert.equal(save.disabled, false);
  assert.match(message.textContent, /public after you save/);
});

test('administrator can suspend rating without an expiry and sees updated access', async () => {
  const calls = [];
  const elements = {
    'rating-ban-student': element({ value: '10' }),
    'rating-ban-reason': element({ value: '  1233  ' }),
    'rating-ban-expiry': element({ value: '' }),
    'rating-ban-message': element(),
    'rating-ban-state': element(),
    'rating-ban-save': element(),
    'rating-unban': element()
  };
  const p = page(elements, async (url, options) => {
    calls.push({ url, options });
    return { ok: true, status: 200, json: async () => ({ banned: true, reason: '1233' }) };
  });
  p.context.admin = { id: 1, roles: ['ADMINISTRATOR'], activeRole: 'ADMINISTRATOR' };
  p.run('currentWorkspaceUser = admin');

  await p.run('banStudentRating({ preventDefault() {} })');

  assert.equal(calls.length, 2);
  assert.match(calls[0].url, /admin\/reviews\/students\/10\/ban$/);
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.headers['X-Active-Role'], 'ADMINISTRATOR');
  assert.deepEqual(JSON.parse(calls[0].options.body), { reason: '1233' });
  assert.equal(calls[1].options.method ?? 'GET', 'GET');
  assert.match(elements['rating-ban-state'].textContent, /suspended/i);
  assert.match(elements['rating-ban-message'].textContent, /suspended/i);
  assert.equal(elements['rating-unban'].disabled, false);
  assert.equal(elements['rating-ban-save'].disabled, false);
});

test('public review markup stays anonymous, hides ticket details, and escapes comments', () => {
  const container = element();
  const p = page();
  p.context.directory = [{ id: 22, name: 'Alex', averageRating: 4.5, ratingCount: 1,
    reviews: [{ id: 6, score: 5, comment: '<img src=x onerror=alert(1)>',
      authorName: 'Must remain private', ticketId: 777, createdAt: '2026-10-14T10:00:00' }] }];
  p.context.directoryContainer = container;
  p.run('renderReviewDirectory(directory, directoryContainer)');
  assert.match(container.innerHTML, /Alex/);
  assert.match(container.innerHTML, /&lt;img/);
  assert.doesNotMatch(container.innerHTML, /Must remain private|777|<img/);
});

test('technician dashboard requests only own average and never individual reviews', async () => {
  const paths = [];
  const section = element({ hidden: true });
  const summary = element();
  const p = page({ 'technician-rating-section': section, 'technician-rating-summary': summary },
    async (url) => {
      paths.push(url);
      return { ok: true, status: 200, json: async () => ({ averageRating: 4.75 }) };
    });
  p.context.technician = { id: 22, roles: ['MAINTENANCE_STAFF'], activeRole: 'MAINTENANCE_STAFF' };
  p.run('currentWorkspaceUser = technician');
  await p.run('loadTechnicianAverage(technician)');
  assert.equal(section.hidden, false);
  assert.equal(summary.textContent, '4.8 / 5');
  assert.deepEqual(paths.map(url => url.split('/api')[1]), ['/reviews/me/average']);
  p.context.student = { id: 10, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
  p.run('currentWorkspaceUser = student');
  await p.run('loadTechnicianAverage(student)');
  assert.equal(section.hidden, true);
  assert.equal(summary.textContent, '');
});

test('notification messages are escaped before display', () => {
  const container = element();
  const p = page();
  p.context.items = [{ id: 1, type: 'REVIEW_BANNED', message: '<script>alert(1)</script>',
    ticketId: null, createdAt: '2026-10-14T10:00:00', readAt: null }];
  p.context.notificationContainer = container;
  p.run('renderNotifications(items, notificationContainer)');
  assert.match(container.innerHTML, /&lt;script&gt;/);
  assert.doesNotMatch(container.innerHTML, /<script>/);
  assert.match(container.innerHTML, /Mark read/);
});

test('administrators cannot moderate their own review or report', () => {
  const reviewList = element();
  const reports = element();
  const p = page({ 'admin-reviews-list': reviewList, 'review-reports-list': reports });
  p.context.admin = { id: 5, roles: ['ADMINISTRATOR'], activeRole: 'ADMINISTRATOR' };
  p.context.reviews = [{ id: 1, ticketId: 7, technicianId: 8, technicianName: 'Tech',
    authorId: 5, authorName: 'Admin', authorEmail: 'user-fb98d44ad750@example.invalid', score: 1,
    comment: 'x', visibility: 'ACTIVE', createdAt: '2026-10-14T10:00:00' }];
  p.context.reports = [{ id: 3, reviewId: 1, authorId: 5, reporterId: 5,
    reporterName: 'Admin', reason: 'reason', status: 'PENDING', createdAt: '2026-10-14T10:00:00' }];
  p.run('currentWorkspaceUser = admin; renderAdminReviews(reviews); renderReviewReports(reports, reviews);');
  assert.doesNotMatch(reviewList.innerHTML, /review-moderation-form/);
  assert.doesNotMatch(reports.innerHTML, /report-resolution-form/);
});

test('administrator moderation actions follow review visibility', () => {
  const reviewList = element();
  const p = page({ 'admin-reviews-list': reviewList });
  p.context.admin = { id: 5, roles: ['ADMINISTRATOR'], activeRole: 'ADMINISTRATOR' };
  p.context.reviews = [{ id: 1, ticketId: 7, technicianId: 8, technicianName: 'Tech',
    authorId: 6, authorName: 'Student', authorEmail: 'user-bfe7cf69b3dd@example.invalid', score: 2,
    comment: 'removed text', visibility: 'TEXT_REMOVED', createdAt: '2026-10-14T10:00:00' }];
  p.run('currentWorkspaceUser = admin; renderAdminReviews(reviews)');
  assert.match(reviewList.innerHTML, /value="RESTORE"/);
  assert.match(reviewList.innerHTML, /value="REMOVE_ALL"/);
  assert.doesNotMatch(reviewList.innerHTML, /value="REMOVE_TEXT"/);
  p.run("reviews[0].visibility = 'ACTIVE'; renderAdminReviews(reviews)");
  assert.match(reviewList.innerHTML, /value="REMOVE_TEXT"/);
  assert.doesNotMatch(reviewList.innerHTML, /value="RESTORE"/);
});

test('pending report shows the reported snapshot separately from the current edited review', () => {
  const reportsList = element();
  const p = page({ 'review-reports-list': reportsList });
  p.context.admin = { id: 1, roles: ['ADMINISTRATOR'], activeRole: 'ADMINISTRATOR' };
  p.context.reviews = [{ id: 6, technicianName: 'Technician', authorId: 3,
    score: 5, comment: 'Edited later', visibility: 'ACTIVE' }];
  p.context.reports = [{ id: 9, reviewId: 6, authorId: 3, reporterId: 4,
    reporterName: 'Reporter', reason: 'Abusive content', status: 'PENDING',
    reportedScore: 1, reportedComment: '<script>Bad original</script>',
    createdAt: '2026-10-14T10:00:00' }];
  p.run('currentWorkspaceUser = admin; renderReviewReports(reports, reviews)');
  assert.match(reportsList.innerHTML, /Content when reported/);
  assert.match(reportsList.innerHTML, /Rating: 1 \/ 5/);
  assert.match(reportsList.innerHTML, /&lt;script&gt;Bad original&lt;\/script&gt;/);
  assert.match(reportsList.innerHTML, /Current review/);
  assert.match(reportsList.innerHTML, /Edited later/);
  assert.doesNotMatch(reportsList.innerHTML, /<script>/);
});

test('report and moderation submissions require reasons and send the selected action', async () => {
  const calls = [];
  const p = page({ 'review-admin-message': element() }, async (url, options) => {
    calls.push({ url, options });
    return { ok: true, status: 200, json: async () => ({}) };
  });
  p.context.student = { id: 10, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
  p.run('currentWorkspaceUser = student; loadUnreadCount = async () => {}');
  const reportMessage = element();
  const reportButton = element();
  const reportReason = element({ value: '  Offensive text  ' });
  const reportForm = {
    dataset: { reviewId: '6' },
    querySelector: selector => ({ '[name="reason"]': reportReason, '[role="status"]': reportMessage,
      'button[type="submit"]': reportButton })[selector]
  };
  p.context.reportForm = reportForm;
  await p.run('submitReviewReport({ preventDefault() {}, currentTarget: reportForm })');
  assert.match(calls[0].url, /reviews\/6\/reports$/);
  assert.deepEqual(JSON.parse(calls[0].options.body), { reason: 'Offensive text' });

  p.context.admin = { id: 1, roles: ['ADMINISTRATOR'], activeRole: 'ADMINISTRATOR' };
  p.run('currentWorkspaceUser = admin; loadReviewModeration = async () => {}');
  const moderationMessage = element();
  const action = element({ value: 'REMOVE_TEXT' });
  const reason = element({ value: '  Personal attack  ' });
  const moderationForm = {
    dataset: { reviewId: '6' },
    querySelector: selector => ({ '[name="action"]': action, '[name="reason"]': reason,
      '[role="status"]': moderationMessage, 'button[type="submit"]': element() })[selector]
  };
  p.context.moderationForm = moderationForm;
  await p.run('submitReviewModeration({ preventDefault() {}, currentTarget: moderationForm })');
  assert.match(calls[1].url, /admin\/reviews\/6\/moderation$/);
  assert.deepEqual(JSON.parse(calls[1].options.body), { action: 'REMOVE_TEXT', reason: 'Personal attack' });
  assert.equal(calls[1].options.headers['X-Active-Role'], 'ADMINISTRATOR');
});

test('marking a notification read uses the active role and clears the local unread state', async () => {
  const calls = [];
  const p = page({}, async (url, options) => {
    calls.push({ url, options });
    return { ok: true, status: 204 };
  });
  p.context.student = { id: 10, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
  p.run('currentWorkspaceUser = student; loadUnreadCount = async () => {}');
  let removed = false;
  const entry = element({ dataset: { notificationId: '9' } });
  const button = element({ closest: () => entry, remove: () => { removed = true; } });
  p.context.button = button;
  await p.run('markNotificationRead(button)');
  assert.match(calls[0].url, /notifications\/9\/read$/);
  assert.equal(calls[0].options.method, 'PUT');
  assert.equal(calls[0].options.headers['X-Active-Role'], 'GENERAL_USER');
  assert.equal(removed, true);
});
