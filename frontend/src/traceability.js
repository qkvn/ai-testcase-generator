// Tiện ích tính ma trận truy vết & độ bao phủ yêu cầu ở phía client
// (tính lại tức thì khi người dùng sửa liên kết, chưa cần lưu xuống DB).

export function computeCoverage(requirements = [], testCases = []) {
  const codes = new Set(requirements.map(r => r.code));
  const byReq = {};
  requirements.forEach(r => { byReq[r.code] = []; });

  const orphans = [];
  testCases.forEach(tc => {
    const ids = (tc.requirementIds || []).filter(c => codes.has(c));
    ids.forEach(c => byReq[c].push(tc.id));
    if (ids.length === 0) orphans.push(tc.id);
  });

  const uncovered = requirements.filter(r => byReq[r.code].length === 0).map(r => r.code);
  const total = requirements.length;
  const covered = total - uncovered.length;
  const percent = total ? Math.round((covered * 1000) / total) / 10 : 0;

  return { byReq, orphans, uncovered, total, covered, percent };
}

export function parseRequirementIds(text) {
  return Array.from(new Set(
    String(text || '')
      .split(/[,;\s]+/)
      .map(s => s.trim().toUpperCase())
      .filter(Boolean)
  ));
}

export function coverageColor(percent) {
  if (percent >= 80) return '#198754';
  if (percent >= 50) return '#fd7e14';
  return '#dc3545';
}

export const priorityStyle = {
  High: { backgroundColor: '#f8d7da', color: '#842029' },
  Medium: { backgroundColor: '#fff3cd', color: '#664d03' },
  Low: { backgroundColor: '#e2e3e5', color: '#41464b' },
};
