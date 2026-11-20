import { useCallback, useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import { reviewText } from './reviewText';

// Tab "Đánh giá": kiểm tra tự động bộ test case + AI đề xuất tình huống còn thiếu + "Thêm vào phiên".

const API_BASE_URL = window.location.hostname === 'localhost' ? 'http://localhost:8080' : '';
const api = axios.create({ baseURL: API_BASE_URL, withCredentials: true });
const CHUNK = 10; // số yêu cầu mỗi lượt gọi AI
const defer = (fn) => { queueMicrotask(fn); };

const SEV = {
  ERROR: { color: '#b91c1c', bg: '#fef2f2', border: '#fecaca', icon: '🔴' },
  WARNING: { color: '#b45309', bg: '#fffbeb', border: '#fde68a', icon: '🟠' },
  INFO: { color: '#1d4ed8', bg: '#eff6ff', border: '#bfdbfe', icon: '🔵' },
};
const TYPE_COLORS = { error: '#dc2626', boundary: '#7b4fe0', invalid: '#ea580c', security: '#0f766e', other: '#475569' };

const errText = (err) => {
  const d = err?.response?.data;
  return (d && (d.message || (typeof d === 'string' ? d : ''))) || err?.message || 'Error';
};

export default function ReviewPanel({ language, sessionId, readOnly, reloadKey, hasUnsavedChanges, onJump, onAdded }) {
  const R = reviewText[language] || reviewText.vi;
  const [report, setReport] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [filter, setFilter] = useState('ALL');
  const [picked, setPicked] = useState(null);         // Set mã yêu cầu được chọn cho AI
  const [progress, setProgress] = useState(null);     // { done, total }
  const [suggestions, setSuggestions] = useState(null);
  const [aiError, setAiError] = useState('');
  const [openKey, setOpenKey] = useState(null);
  const [toast, setToast] = useState('');

  const load = useCallback(async () => {
    try {
      const res = await api.get(`/api/sessions/${sessionId}/review`);
      setReport(res.data);
      setLoadError('');
      setPicked(prev => prev ?? new Set(res.data.requirements.filter(r => r.weak).map(r => r.code)));
    } catch (err) {
      setLoadError(errText(err));
    }
  }, [sessionId]);

  useEffect(() => { defer(load); }, [load, reloadKey]);

  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(() => setToast(''), 3000);
    return () => clearTimeout(timer);
  }, [toast]);

  const issues = useMemo(() => (report?.issues || []).filter(i => filter === 'ALL' || i.severity === filter), [report, filter]);

  const runSuggest = async () => {
    const codes = [...picked];
    if (codes.length === 0) return;
    setSuggestions([]);
    setAiError('');
    setProgress({ done: 0, total: codes.length });
    const all = [];
    for (let i = 0; i < codes.length; i += CHUNK) {
      const part = codes.slice(i, i + CHUNK);
      try {
        const res = await api.post(`/api/sessions/${sessionId}/review/suggest`, { requirementIds: part, lang: language });
        (res.data.suggestions || []).forEach((s, k) => all.push({ ...s, key: `${i}-${k}-${s.requirementId}`, status: 'new' }));
        setSuggestions([...all]);
      } catch (err) {
        setAiError(R.failed + errText(err));
      }
      setProgress({ done: Math.min(i + CHUNK, codes.length), total: codes.length });
    }
    setProgress(null);
  };

  const addSuggestions = async (items) => {
    const fresh = items.filter(s => s.status === 'new');
    if (fresh.length === 0) return;
    const keys = new Set(fresh.map(s => s.key));
    setSuggestions(prev => prev.map(s => keys.has(s.key) ? { ...s, status: 'adding' } : s));
    try {
      const body = fresh.map(s => ({
        summary: s.summary, precondition: s.precondition, steps: s.steps,
        expected_result: s.expected_result, requirementIds: [s.requirementId],
      }));
      const res = await api.post(`/api/sessions/${sessionId}/testcases`, body);
      setSuggestions(prev => prev.map(s => keys.has(s.key) ? { ...s, status: 'added' } : s));
      setToast(R.addedToast(res.data.length));
      onAdded(res.data);
    } catch (err) {
      setSuggestions(prev => prev.map(s => keys.has(s.key) ? { ...s, status: 'new' } : s));
      setAiError(R.failed + errText(err));
    }
  };

  if (loadError) return <div className="rv-empty">⚠️ {loadError}</div>;
  if (!report) return <div className="rv-empty"><span className="app-spinner app-spinner-sm" style={{ borderColor: '#dbe4ff', borderTopColor: '#1f4fd8' }} /> {R.loading}</div>;

  const score = report.score;
  const scoreColor = score == null ? '#94a3b8' : score >= 80 ? '#15803d' : score >= 50 ? '#ea580c' : '#dc2626';
  const reqDesc = Object.fromEntries(report.requirements.map(r => [r.code, r.description]));
  const groups = {};
  (suggestions || []).forEach(s => { (groups[s.requirementId] = groups[s.requirementId] || []).push(s); });

  return (
    <div>
      <style>{rvCss}</style>
      {hasUnsavedChanges && <div className="rv-note">💾 {R.unsaved}</div>}

      {/* ---------- Điểm & thống kê ---------- */}
      <div className="rv-summary">
        <div className="rv-score" style={{ borderColor: scoreColor }}>
          <div className="rv-score-num" style={{ color: scoreColor }}>{score ?? '—'}</div>
          <div className="rv-score-label">{R.scoreTitle}</div>
        </div>
        <div style={{ flex: 1, minWidth: 220 }}>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
            {[['ALL', R.all, report.issues.length], ['ERROR', R.errors, report.errors], ['WARNING', R.warnings, report.warnings], ['INFO', R.infos, report.infos]].map(([key, label, n]) => (
              <button key={key} className={'rv-chip' + (filter === key ? ' on' : '')} onClick={() => setFilter(key)}
                style={key !== 'ALL' ? { color: SEV[key].color } : undefined}>
                {key !== 'ALL' && SEV[key].icon + ' '}{label} <b>{n}</b>
              </button>
            ))}
            <button className="rv-chip" onClick={load} style={{ marginLeft: 'auto' }}>{R.recheck}</button>
          </div>
          <div className="rv-muted">{R.scoreHint}</div>
        </div>
      </div>

      {/* ---------- Danh sách vấn đề ---------- */}
      {report.issues.length === 0 ? (
        <div className="rv-empty" style={{ marginBottom: 20 }}>{R.noIssues}</div>
      ) : (
        <div className="rv-issues">
          {issues.map((i, idx) => {
            const sev = SEV[i.severity];
            const label = i.targets.join(', ');
            const clickable = i.targetType === 'TC' && i.dbIds?.length > 0;
            return (
              <div key={idx} className={'rv-issue' + (clickable ? ' clickable' : '')} style={{ background: sev.bg, borderColor: sev.border }}
                onClick={clickable ? () => onJump(i.dbIds[0]) : undefined} title={clickable ? R.clickToFix : ''}>
                <span>{sev.icon}</span>
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ color: sev.color, fontWeight: 600 }}>{(R.issue[i.code] || ((t) => `${i.code}: ${t}`))(label)}</div>
                  {i.detail && <div className="rv-detail">{i.detail}</div>}
                </div>
                {clickable && <span className="rv-go">→</span>}
              </div>
            );
          })}
        </div>
      )}

      {/* ---------- AI đề xuất ---------- */}
      <div className="rv-ai">
        <h3 style={{ margin: '0 0 4px', fontSize: 17 }}>{R.aiTitle}</h3>
        {readOnly ? <p className="rv-muted">{R.readOnly}</p> : report.requirements.length === 0 ? (
          <p className="rv-muted">{R.noRequirements}</p>
        ) : (
          <>
            <p className="rv-muted" style={{ marginTop: 0 }}>{R.aiDesc}</p>
            <div style={{ display: 'flex', gap: 8, marginBottom: 8, flexWrap: 'wrap' }}>
              <button className="rv-link" onClick={() => setPicked(new Set(report.requirements.map(r => r.code)))}>{R.pickAll}</button>
              <button className="rv-link" onClick={() => setPicked(new Set(report.requirements.filter(r => r.weak).map(r => r.code)))}>{R.pickWeak}</button>
              <button className="rv-link" onClick={() => setPicked(new Set())}>{R.pickNone}</button>
            </div>
            <div className="rv-reqs">
              {report.requirements.map(r => (
                <label key={r.code} className={'rv-req' + (picked?.has(r.code) ? ' on' : '')}>
                  <input type="checkbox" checked={!!picked?.has(r.code)} disabled={!!progress}
                    onChange={() => setPicked(prev => { const n = new Set(prev); if (n.has(r.code)) n.delete(r.code); else n.add(r.code); return n; })} />
                  <b>{r.code}</b>
                  <span className="rv-req-desc" title={r.description}>{r.description}</span>
                  {r.weak && <span className="rv-badge rv-badge-warn">{R.weak}</span>}
                  <span className="rv-badge">{R.tcCount(r.testCaseCount)}</span>
                </label>
              ))}
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 12 }}>
              <button className="app-btn app-btn-orange app-btn-lg" disabled={!picked?.size || !!progress} onClick={runSuggest}>
                {progress ? <><span className="app-spinner app-spinner-sm" /> {R.suggesting(progress.done, progress.total)}</> : R.suggestBtn(picked?.size || 0)}
              </button>
            </div>
            {progress && <div className="rv-bar"><div style={{ width: Math.round((progress.done / progress.total) * 100) + '%' }} /></div>}
            {aiError && <div className="rv-error">⚠️ {aiError}</div>}

            {suggestions && !progress && suggestions.length === 0 && !aiError && <div className="rv-empty" style={{ marginTop: 12 }}>✅ {R.noSuggestions}</div>}
            {Object.entries(groups).map(([code, items]) => {
              const pending = items.filter(s => s.status === 'new').length;
              return (
                <div key={code} className="rv-group">
                  <div className="rv-group-head">
                    <div style={{ minWidth: 0 }}>
                      <b>{R.suggestionsFor(code)}</b>
                      <div className="rv-detail" title={reqDesc[code]}>{reqDesc[code]}</div>
                    </div>
                    {pending > 1 && <button className="app-btn app-btn-primary" onClick={() => addSuggestions(items)}>{R.addAll(pending)}</button>}
                  </div>
                  {items.map(s => (
                    <div key={s.key} className={'rv-sug' + (s.status === 'added' ? ' added' : '')}>
                      <div className="rv-sug-row">
                        <span className="rv-type" style={{ color: TYPE_COLORS[s.type], borderColor: TYPE_COLORS[s.type] + '55' }}>{R.types[s.type] || s.type}</span>
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{ fontWeight: 600 }}>{s.summary}</div>
                          {s.reason && <div className="rv-detail">💡 {s.reason}</div>}
                        </div>
                        <button className="rv-link" onClick={() => setOpenKey(openKey === s.key ? null : s.key)}>{openKey === s.key ? R.hideDetail : R.showDetail}</button>
                        {s.status === 'added' ? <span className="rv-added">{R.added}</span>
                          : <button className="app-btn app-btn-green" disabled={s.status === 'adding'} onClick={() => addSuggestions([s])}>{s.status === 'adding' ? R.adding : R.addOne}</button>}
                      </div>
                      {openKey === s.key && (
                        <div className="rv-sug-detail">
                          {s.precondition && <div><b>{R.precondition}:</b> {s.precondition}</div>}
                          {s.steps?.length > 0 && <div><b>{R.steps}:</b><ol>{s.steps.map((st, k) => <li key={k}>{st}</li>)}</ol></div>}
                          {s.expected_result && <div><b>{R.expected}:</b> {s.expected_result}</div>}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              );
            })}
          </>
        )}
      </div>
      {toast && <div className="rv-toast">✅ {toast}</div>}
    </div>
  );
}

const rvCss = `
.rv-note { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; border-radius: 10px; padding: 9px 12px; font-size: 13.5px; margin-bottom: 14px; }
.rv-summary { display: flex; gap: 18px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
.rv-score { width: 104px; height: 104px; border-radius: 50%; border: 6px solid; display: flex; flex-direction: column; align-items: center; justify-content: center; flex-shrink: 0; box-sizing: border-box; }
.rv-score-num { font-size: 30px; font-weight: 800; line-height: 1; }
.rv-score-label { font-size: 11px; color: #64748b; margin-top: 4px; text-align: center; }
.rv-chip { border: 1px solid #e2e8f0; background: #fff; border-radius: 20px; padding: 5px 12px; font-size: 13px; cursor: pointer; font-family: inherit; color: #334155; }
.rv-chip.on { background: #eef3ff; border-color: #b9c8f5; }
.rv-chip:hover { background: #f8fafc; }
.rv-muted { color: #64748b; font-size: 13px; }
.rv-empty { text-align: center; padding: 28px 16px; color: #64748b; border: 1px dashed #d8dfea; border-radius: 12px; display: flex; gap: 8px; align-items: center; justify-content: center; }
.rv-issues { display: flex; flex-direction: column; gap: 6px; max-height: 340px; overflow-y: auto; margin-bottom: 22px; padding-right: 2px; }
.rv-issue { display: flex; gap: 10px; align-items: flex-start; border: 1px solid; border-radius: 10px; padding: 9px 12px; font-size: 14px; }
.rv-issue.clickable { cursor: pointer; transition: transform .1s, box-shadow .15s; }
.rv-issue.clickable:hover { box-shadow: 0 4px 12px rgba(15,23,42,0.08); }
.rv-go { color: #64748b; font-weight: 700; }
.rv-detail { color: #64748b; font-size: 12.5px; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rv-ai { border-top: 1px solid #e8ecf2; padding-top: 18px; }
.rv-link { background: none; border: none; color: #1f4fd8; font-weight: 600; font-size: 13px; cursor: pointer; padding: 2px 4px; font-family: inherit; white-space: nowrap; }
.rv-link:hover { text-decoration: underline; }
.rv-reqs { display: flex; flex-direction: column; gap: 4px; max-height: 260px; overflow-y: auto; }
.rv-req { display: flex; align-items: center; gap: 8px; padding: 7px 10px; border: 1px solid #e8ecf2; border-radius: 10px; cursor: pointer; font-size: 14px; }
.rv-req.on { background: #f8faff; border-color: #b9c8f5; }
.rv-req input { accent-color: #1f4fd8; width: 16px; height: 16px; flex-shrink: 0; }
.rv-req-desc { flex: 1; min-width: 0; color: #475569; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rv-badge { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 10px; background: #f1f5f9; color: #475569; white-space: nowrap; }
.rv-badge-warn { background: #fff7ed; color: #c2410c; }
.rv-bar { height: 6px; background: #eef1f6; border-radius: 4px; overflow: hidden; margin-top: 10px; }
.rv-bar > div { height: 100%; background: linear-gradient(90deg, #1f4fd8, #7b4fe0); transition: width .4s; }
.rv-error { margin-top: 10px; background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; border-radius: 10px; padding: 9px 12px; font-size: 13px; }
.rv-group { margin-top: 16px; border: 1px solid #e8ecf2; border-radius: 14px; padding: 12px; }
.rv-group-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 8px; }
.rv-sug { border-top: 1px solid #f1f4f8; padding: 10px 2px; }
.rv-sug:first-of-type { border-top: none; }
.rv-sug.added { opacity: .6; }
.rv-sug-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.rv-type { font-size: 11.5px; font-weight: 700; border: 1px solid; border-radius: 10px; padding: 2px 8px; white-space: nowrap; }
.rv-added { color: #15803d; font-weight: 700; font-size: 13px; white-space: nowrap; }
.rv-sug-detail { margin: 8px 0 0 4px; font-size: 13.5px; color: #334155; background: #fafbfd; border-radius: 10px; padding: 10px 12px; line-height: 1.6; }
.rv-sug-detail ol { margin: 2px 0 4px; padding-left: 20px; }
.rv-toast { position: fixed; bottom: 22px; right: 22px; z-index: 70; background: #0f172a; color: #fff; padding: 11px 16px; border-radius: 12px; font-size: 14px; box-shadow: 0 12px 30px rgba(15,23,42,0.3); }
`;
