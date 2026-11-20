import { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { fixText } from './fixText';

// "AI rà soát & sửa": tìm test case trùng / sai, hiện đề xuất (trước / sau), người dùng duyệt rồi mới áp dụng; có hoàn tác.

const API_BASE_URL = window.location.hostname === 'localhost' ? 'http://localhost:8080' : '';
const api = axios.create({ baseURL: API_BASE_URL, withCredentials: true });
const CHUNK = 8;        // số test case mỗi lượt gọi AI (nhỏ để không vượt giới hạn token/phút của Groq miễn phí)
const MAX_SCAN = 120;   // tối đa test case rà soát trong một lần
const AUTO_KEY = 'fixAutoApply';
const defer = (fn) => { queueMicrotask(fn); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const CONF = {
  high: { color: '#15803d', bg: '#f0fdf4', border: '#bbf7d0' },
  medium: { color: '#b45309', bg: '#fffbeb', border: '#fde68a' },
  low: { color: '#64748b', bg: '#f8fafc', border: '#e2e8f0' },
};

const errText = (err) => {
  const d = err?.response?.data;
  return (d && (d.message || (typeof d === 'string' ? d : ''))) || err?.message || 'Error';
};

const readAuto = () => { try { return localStorage.getItem(AUTO_KEY) === '1'; } catch { return false; } };
const writeAuto = (v) => { try { localStorage.setItem(AUTO_KEY, v ? '1' : '0'); } catch { /* bỏ qua */ } };

/** So sánh theo dãy con chung dài nhất (LCS) -> [{ t, type: 'same' | 'del' | 'add' }]. */
function diff(a, b) {
  const n = a.length, m = b.length;
  const dp = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) {
    dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  }
  const out = [];
  let i = 0, j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) { out.push({ t: a[i], type: 'same' }); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) out.push({ t: a[i++], type: 'del' });
    else out.push({ t: b[j++], type: 'add' });
  }
  while (i < n) out.push({ t: a[i++], type: 'del' });
  while (j < m) out.push({ t: b[j++], type: 'add' });
  return out;
}

const words = (s) => (s || '').split(/(\s+)/).filter(x => x !== '');
const norm = (s) => (s || '').trim().replace(/\s+/g, ' ');

function TextDiff({ before, after }) {
  const parts = diff(words(before), words(after));
  const solid = (x) => x.t.trim() !== '';
  const same = parts.filter(x => x.type === 'same' && solid(x)).length;
  const longest = Math.max(words(before).filter(w => w.trim()).length, words(after).filter(w => w.trim()).length);
  // Viết lại gần hết câu -> hiện nguyên câu cũ rồi nguyên câu mới cho dễ đọc
  if (before && after && longest > 0 && same / longest < 0.5) {
    return (
      <span className="fx-rewrite">
        <span className="fx-del">{before}</span>
        <span className="fx-add">{after}</span>
      </span>
    );
  }
  return (
    <span>
      {parts.map((p, k) => (
        <span key={k} className={p.type === 'del' ? 'fx-del' : p.type === 'add' ? 'fx-add' : undefined}>{p.t}</span>
      ))}
    </span>
  );
}

function StepsDiff({ before, after }) {
  return (
    <ol className="fx-steps">
      {diff((before || []).map(norm), (after || []).map(norm)).map((p, k) => (
        <li key={k} className={p.type === 'del' ? 'fx-line-del' : p.type === 'add' ? 'fx-line-add' : undefined}>{p.t}</li>
      ))}
    </ol>
  );
}

/** Các trường bị đổi giữa before / after. */
function changedFields(f) {
  const b = f.before || {}, a = f.after || {};
  const out = [];
  ['summary', 'precondition', 'expected_result'].forEach(k => { if (norm(b[k]) !== norm(a[k])) out.push(k); });
  if (JSON.stringify((b.steps || []).map(norm)) !== JSON.stringify((a.steps || []).map(norm))) out.push('steps');
  if (JSON.stringify(b.requirementIds || []) !== JSON.stringify(a.requirementIds || [])) out.push('requirementIds');
  return out;
}

export default function FixPanel({ language, sessionId, testCases, hasUnsavedChanges, autoScan, onAutoScanDone, onReplaced, onChanged, onJump }) {
  const F = fixText[language] || fixText.vi;
  const [fixes, setFixes] = useState(null);
  const [progress, setProgress] = useState(null);   // { stage: 'dup' | 'ai', done, total }
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');
  const [autoApply, setAutoApply] = useState(readAuto);
  const [undoCount, setUndoCount] = useState(0);
  const [undoing, setUndoing] = useState(false);
  const [banner, setBanner] = useState('');
  const [limited, setLimited] = useState(false);
  const lastBatch = useRef([]);
  const handledNonce = useRef(null);

  const byDbId = Object.fromEntries((testCases || []).map(t => [t.dbId, t]));
  const allIds = (testCases || []).map(t => t.dbId).filter(Boolean);

  useEffect(() => {
    let alive = true;
    api.get(`/api/sessions/${sessionId}/review/fixes/undo`)
      .then(res => { if (alive) setUndoCount(res.data.undoCount || 0); })
      .catch(() => {});
    return () => { alive = false; };
  }, [sessionId]);

  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(() => setToast(''), 3500);
    return () => clearTimeout(timer);
  }, [toast]);

  const apply = useCallback(async (items, auto = false) => {
    const fresh = items.filter(f => f.status === 'new');
    if (fresh.length === 0) return;
    if (hasUnsavedChanges) { setError(F.unsavedBlock); return; }
    setError('');
    const keys = new Set(fresh.map(f => f.key));
    setFixes(prev => prev.map(f => keys.has(f.key) ? { ...f, status: 'applying' } : f));
    // Sửa nội dung trước, gộp trùng sau
    const ordered = [...fresh].sort((x, y) => (x.kind === 'merge') - (y.kind === 'merge'));
    try {
      const res = await api.post(`/api/sessions/${sessionId}/review/fixes/apply`, {
        items: ordered.map(f => ({ kind: f.kind, dbId: f.dbId, before: f.before, after: f.after, removeDbIds: f.removeDbIds })),
      });
      const skipped = new Set(res.data.skippedDbIds || []);
      setFixes(prev => prev.map(f => keys.has(f.key) ? { ...f, status: skipped.has(f.dbId) ? 'stale' : 'applied' } : f));
      lastBatch.current = fresh.filter(f => !skipped.has(f.dbId)).map(f => f.key);
      setUndoCount(res.data.undoCount || 0);
      setToast(auto ? F.autoAppliedToast(res.data.applied) : F.appliedToast(res.data.applied, res.data.skipped));
      onReplaced(res.data.testCases || []);
      onChanged();
    } catch (err) {
      setFixes(prev => prev.map(f => keys.has(f.key) ? { ...f, status: 'new' } : f));
      setError(F.applyFailed + errText(err));
    }
  }, [sessionId, hasUnsavedChanges, F, onReplaced, onChanged]);

  const scan = useCallback(async (scopeIds) => {
    setFixes(null);
    setError('');
    setProgress({ stage: 'dup', done: 0, total: 0 });
    const found = [];
    try {
      const dup = await api.post(`/api/sessions/${sessionId}/review/fixes/duplicates`, { dbIds: scopeIds || [] });
      (dup.data.fixes || []).forEach(f => found.push({ ...f, status: 'new' }));
    } catch (err) {
      setError(F.failed + errText(err));
    }
    const exclude = found.flatMap(f => f.removeDbIds || []);
    const excludeSet = new Set(exclude);
    const targets = (scopeIds && scopeIds.length ? scopeIds : allIds).filter(id => !excludeSet.has(id));
    setLimited(targets.length > MAX_SCAN);
    const list = targets.slice(0, MAX_SCAN);
    setFixes([...found]);
    let missed = 0;
    for (let i = 0; i < list.length; i += CHUNK) {
      const part = list.slice(i, i + CHUNK);
      setProgress({ stage: 'ai', done: i, total: list.length });
      for (let attempt = 1; attempt <= 2; attempt++) {
        try {
          const res = await api.post(`/api/sessions/${sessionId}/review/fixes/ai`, {
            dbIds: part, excludeDbIds: exclude, lang: language,
          });
          (res.data.fixes || []).forEach(f => found.push({ ...f, key: `${f.key}-${i}`, status: 'new' }));
          setFixes([...found]);
          break;
        } catch (err) {
          const limited = err?.response?.status === 429;
          if (limited && attempt === 1) {
            // Groq giới hạn token/phút -> chờ rồi thử lại lượt này một lần
            const wait = Math.min(60000, Math.max(15000, err.response.data?.retryAfterMs || 0));
            setProgress({ stage: 'wait', done: i, total: list.length, seconds: Math.round(wait / 1000) });
            await sleep(wait);
            setProgress({ stage: 'ai', done: i, total: list.length });
            continue;
          }
          missed += part.length;
          if (!limited) setError(F.failed + errText(err));
          break;
        }
      }
    }
    if (missed > 0) setError(F.rateLimited(missed));
    setProgress(null);
    if (autoApply) {
      const high = found.filter(f => f.confidence === 'high');
      if (high.length) await apply(high, true);
    }
  }, [sessionId, language, allIds, autoApply, apply, F]);

  // Tự rà soát sau khi tải tài liệu SRS
  useEffect(() => {
    if (!autoScan || handledNonce.current === autoScan.nonce) return;
    handledNonce.current = autoScan.nonce;
    defer(() => {
      onAutoScanDone();
      setBanner(F.autoBanner(autoScan.dbIds.length));
      scan(autoScan.dbIds);
    });
  }, [autoScan, onAutoScanDone, scan, F]);

  const undo = async () => {
    if (hasUnsavedChanges) { setError(F.unsavedBlock); return; }
    setUndoing(true);
    setError('');
    try {
      const res = await api.post(`/api/sessions/${sessionId}/review/fixes/undo`);
      setUndoCount(res.data.undoCount || 0);
      if (res.data.undone) {
        const keys = new Set(lastBatch.current);
        lastBatch.current = [];
        setFixes(prev => prev && prev.map(f => keys.has(f.key) ? { ...f, status: 'new' } : f));
        setToast(F.undone);
        onReplaced(res.data.testCases || []);
        onChanged();
      }
    } catch (err) {
      setError(F.applyFailed + errText(err));
    } finally {
      setUndoing(false);
    }
  };

  const pending = (fixes || []).filter(f => f.status === 'new');
  const pendingHigh = pending.filter(f => f.confidence === 'high');
  const busy = !!progress;

  return (
    <div className="fx">
      <style>{fxCss}</style>
      <div className="fx-head">
        <div style={{ minWidth: 0 }}>
          <h3 style={{ margin: '0 0 4px', fontSize: 17 }}>{F.title}</h3>
          <p className="rv-muted" style={{ margin: 0 }}>{F.desc}</p>
        </div>
      </div>

      {banner && <div className="fx-banner">📄 {banner}</div>}

      <div className="fx-toolbar">
        <label className="fx-toggle" title={F.autoApplyHint}>
          <input type="checkbox" checked={autoApply} onChange={(e) => { setAutoApply(e.target.checked); writeAuto(e.target.checked); }} />
          <span>{F.autoApply}</span>
        </label>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginLeft: 'auto' }}>
          {undoCount > 0 && (
            <button className="app-btn" disabled={undoing || busy} onClick={undo}>{undoing ? F.undoing : F.undo(undoCount)}</button>
          )}
          <button className="app-btn app-btn-primary" disabled={busy || allIds.length === 0} onClick={() => { setBanner(''); scan(null); }}>
            {busy ? <><span className="app-spinner app-spinner-sm" /> {progress.stage === 'dup' ? F.scanDup : progress.stage === 'wait' ? F.waiting(progress.seconds) : F.scanAi(progress.done, progress.total)}</>
              : F.scanBtn(Math.min(allIds.length, MAX_SCAN))}
          </button>
        </div>
      </div>
      {busy && progress.stage !== 'dup' && progress.total > 0 && (
        <div className="rv-bar"><div style={{ width: Math.round((progress.done / progress.total) * 100) + '%' }} /></div>
      )}
      {limited && <div className="rv-muted" style={{ marginTop: 6 }}>{F.limited(MAX_SCAN)}</div>}
      {error && <div className="rv-error">⚠️ {error}</div>}

      {fixes && !busy && fixes.length === 0 && !error && <div className="rv-empty" style={{ marginTop: 12 }}>{F.noFixes}</div>}

      {pending.length > 0 && !busy && (
        <div className="fx-bulk">
          {pendingHigh.length > 0 && pendingHigh.length < pending.length && (
            <button className="app-btn app-btn-green" onClick={() => apply(pendingHigh)}>{F.applyHigh(pendingHigh.length)}</button>
          )}
          <button className="app-btn app-btn-green" onClick={() => apply(pending)}>{F.applyAll(pending.length)}</button>
          <button className="app-btn" onClick={() => setFixes(prev => prev.map(f => f.status === 'new' ? { ...f, status: 'dismissed' } : f))}>{F.dismissAll}</button>
        </div>
      )}

      {(fixes || []).map(f => {
        const c = CONF[f.confidence] || CONF.medium;
        const done = f.status === 'applied' || f.status === 'dismissed';
        return (
          <div key={f.key} className={'fx-card' + (done ? ' done' : '')}>
            <div className="fx-card-head">
              <span className="fx-kind">{F.kind[f.kind]}</span>
              <button className="fx-tc" onClick={() => onJump(f.dbId)} title={F.open}>{f.tcId}</button>
              <span className="fx-badge">{F.problem[f.problem] || f.problem}</span>
              <span className="fx-badge" style={{ color: c.color, background: c.bg, borderColor: c.border }}>{F.conf[f.confidence]}</span>
              <span className="fx-src">{F.source[f.source]}</span>
              <div style={{ marginLeft: 'auto', display: 'flex', gap: 6, alignItems: 'center' }}>
                {f.status === 'new' && <>
                  <button className="rv-link" onClick={() => setFixes(prev => prev.map(x => x.key === f.key ? { ...x, status: 'dismissed' } : x))}>{F.dismiss}</button>
                  <button className="app-btn app-btn-green" onClick={() => apply([f])}>{F.apply}</button>
                </>}
                {f.status === 'applying' && <span className="rv-muted">{F.applying}</span>}
                {f.status === 'applied' && <span className="rv-added">{F.applied}</span>}
                {f.status === 'dismissed' && <span className="rv-muted">{F.dismissed}</span>}
                {f.status === 'stale' && <span className="fx-stale">{F.stale}</span>}
              </div>
            </div>

            {f.kind === 'merge' ? (
              <div className="fx-body">
                <div style={{ fontWeight: 600 }}>
                  {F.mergeKeep(f.tcId, f.removeTcIds.join(', '))}
                  {f.similarity != null && <span className="rv-muted"> · {F.similarity(f.similarity)}</span>}
                </div>
                <div className="rv-detail" style={{ whiteSpace: 'normal' }}>💡 {f.reason || F.mergeDefault}</div>
                <div className="fx-merge">
                  <div><span className="fx-keep">{f.tcId}</span> {f.before?.summary}</div>
                  {f.removeDbIds.map((id, k) => (
                    <div key={id} className="fx-line-del"><span className="fx-rm">{f.removeTcIds[k]}</span> {byDbId[id]?.summary || ''} <i className="rv-muted">({F.removed})</i></div>
                  ))}
                </div>
                {JSON.stringify(f.before?.requirementIds || []) !== JSON.stringify(f.after?.requirementIds || []) && (
                  <div className="fx-field"><b>{F.mergeLinks}:</b> <TextDiff before={(f.before?.requirementIds || []).join(', ')} after={(f.after?.requirementIds || []).join(', ')} /></div>
                )}
              </div>
            ) : (
              <div className="fx-body">
                {f.reason && <div className="rv-detail" style={{ whiteSpace: 'normal' }}>💡 {f.reason}</div>}
                {changedFields(f).map(k => (
                  <div key={k} className="fx-field">
                    <b>{F.field[k]}:</b>{' '}
                    {k === 'steps' ? <StepsDiff before={f.before.steps} after={f.after.steps} />
                      : k === 'requirementIds' ? <TextDiff before={(f.before.requirementIds || []).join(', ')} after={(f.after.requirementIds || []).join(', ')} />
                        : <TextDiff before={f.before[k]} after={f.after[k]} />}
                  </div>
                ))}
                {f.evidence && (
                  <div className={'fx-evidence' + (f.evidenceVerified === false ? ' bad' : '')}>
                    📄 <b>{F.evidence}:</b> “{f.evidence}”
                    {f.evidenceVerified === false && <div className="fx-warn">⚠ {F.evidenceBad}</div>}
                  </div>
                )}
              </div>
            )}
          </div>
        );
      })}
      {toast && <div className="rv-toast">✅ {toast}</div>}
    </div>
  );
}

const fxCss = `
.fx { border-top: 1px solid #e8ecf2; padding-top: 18px; margin-bottom: 22px; }
.fx-banner { background: #eef3ff; border: 1px solid #c7d5fb; color: #1e3a8a; border-radius: 10px; padding: 9px 12px; font-size: 13.5px; margin-top: 10px; }
.fx-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
.fx-toggle { display: flex; align-items: center; gap: 8px; font-size: 13.5px; color: #334155; cursor: pointer; }
.fx-toggle input { accent-color: #1f4fd8; width: 16px; height: 16px; }
.fx-bulk { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; margin-top: 14px; }
.fx-card { margin-top: 12px; border: 1px solid #e8ecf2; border-radius: 14px; padding: 12px 14px; background: #fff; }
.fx-card.done { opacity: .55; }
.fx-card-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.fx-kind { font-weight: 700; font-size: 14px; }
.fx-tc { border: 1px solid #c7d5fb; background: #eef3ff; color: #1f4fd8; font-weight: 700; font-size: 12.5px; border-radius: 8px; padding: 2px 8px; cursor: pointer; font-family: inherit; }
.fx-tc:hover { background: #dfe8ff; }
.fx-badge { font-size: 11.5px; font-weight: 700; border: 1px solid #e2e8f0; background: #f8fafc; color: #475569; border-radius: 10px; padding: 2px 8px; white-space: nowrap; }
.fx-src { font-size: 11.5px; color: #94a3b8; }
.fx-stale { color: #b45309; font-size: 12.5px; font-weight: 600; }
.fx-body { margin-top: 8px; font-size: 13.5px; color: #334155; display: flex; flex-direction: column; gap: 6px; }
.fx-field { background: #fafbfd; border-radius: 10px; padding: 8px 10px; line-height: 1.6; }
.fx-rewrite { display: inline-flex; flex-direction: column; gap: 3px; vertical-align: top; }
.fx-del { background: #fee2e2; color: #991b1b; text-decoration: line-through; border-radius: 3px; margin-right: 3px; }
.fx-add { background: #dcfce7; color: #166534; border-radius: 3px; }
.fx-steps { margin: 4px 0 0; padding-left: 22px; }
.fx-line-del { background: #fef2f2; color: #991b1b; text-decoration: line-through; border-radius: 4px; }
.fx-line-add { background: #f0fdf4; color: #166534; border-radius: 4px; }
.fx-merge { background: #fafbfd; border-radius: 10px; padding: 8px 10px; display: flex; flex-direction: column; gap: 4px; }
.fx-keep, .fx-rm { display: inline-block; font-weight: 700; font-size: 12px; border-radius: 6px; padding: 0 6px; margin-right: 4px; }
.fx-keep { background: #dcfce7; color: #166534; }
.fx-rm { background: #fee2e2; color: #991b1b; }
.fx-evidence { border-left: 3px solid #86efac; background: #f7fdf9; padding: 6px 10px; border-radius: 0 8px 8px 0; font-size: 13px; }
.fx-evidence.bad { border-left-color: #fca5a5; background: #fffafa; }
.fx-warn { color: #b91c1c; font-weight: 600; margin-top: 2px; }
`;
