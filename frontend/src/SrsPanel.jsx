import { useState } from 'react';
import { srsText } from './srsText';

// Bảng xem trước tài liệu SRS: chọn phần, xem / sửa nội dung, theo dõi tiến độ sinh test case.
export default function SrsPanel({ language, doc, run, onClose, onGenerate, onStop, onRetry, onViewResult }) {
  const S = srsText[language] || srsText.vi;
  const [selected, setSelected] = useState(() => new Set(doc.sections.filter(s => s.suggested).map(s => s.index)));
  const [edits, setEdits] = useState({});
  const [open, setOpen] = useState(null);

  const running = run && !run.done;
  const toggle = (i) => setSelected(prev => {
    const next = new Set(prev);
    if (next.has(i)) next.delete(i); else next.add(i);
    return next;
  });
  const chosen = doc.sections.filter(s => selected.has(s.index)).map(s => ({ ...s, content: edits[s.index] ?? s.content }));

  // ---------- Đang sinh / đã xong ----------
  if (run) {
    const pct = run.total ? Math.round(((run.done ? run.total : run.current - 1) / run.total) * 100) : 0;
    return (
      <div className="srs-card">
        <style>{srsCss}</style>
        <div className="srs-head">
          <div className="srs-file">📄 <span>{doc.fileName}</span></div>
          {!running && <button className="srs-x" onClick={onClose} aria-label={S.close}>✕</button>}
        </div>
        {running ? (
          <>
            <div className="srs-progress-label">
              <span className="app-spinner app-spinner-sm" style={{ borderColor: '#dbe4ff', borderTopColor: '#1f4fd8' }} />
              <b>{S.progress(run.current, run.total)}</b>
              <span className="srs-muted">— {run.title}</span>
            </div>
            <div className="srs-bar"><div style={{ width: pct + '%' }} /></div>
            <div className="srs-row-between">
              <span className="srs-muted">{S.soFar(run.tcCount)}</span>
              {run.stopping ? <span className="srs-muted">{S.stopping}</span> : <button className="srs-btn" onClick={onStop}>⏹ {S.stop}</button>}
            </div>
          </>
        ) : (
          <>
            <div className="srs-bar"><div style={{ width: (run.stopped ? pct : 100) + '%' }} /></div>
            <div className="srs-muted" style={{ marginTop: 8 }}>{S.soFar(run.tcCount)}</div>
            {run.stopped && <div className="srs-note">⏹ {S.stopped}</div>}
            {run.failed.length > 0 && (
              <div className="srs-error-box">
                <b>⚠️ {S.doneWithErrors(run.failed.length)}</b>
                <ul>{run.failed.map(f => <li key={f.index}><b>{f.title}</b>: {f.error}</li>)}</ul>
              </div>
            )}
            <div className="srs-actions">
              {run.failed.length > 0 && <button className="srs-btn" onClick={() => onRetry(run.failed)}>{S.retryFailed}</button>}
              {run.sessionId && <button className="app-btn app-btn-orange app-btn-lg" onClick={onViewResult}>{S.viewResult} →</button>}
            </div>
          </>
        )}
      </div>
    );
  }

  // ---------- Chọn phần ----------
  return (
    <div className="srs-card">
      <style>{srsCss}</style>
      <div className="srs-head">
        <div style={{ minWidth: 0 }}>
          <div className="srs-file">📄 <span>{doc.fileName}</span></div>
          <div className="srs-meta">
            <span className="srs-tag">{doc.format.toUpperCase()}</span>
            <span>{S.pages(doc.pages)}</span>·<span>{S.chars(doc.chars)}</span>·<span>{S.sectionsCount(doc.sections.length)}</span>
          </div>
        </div>
        <button className="srs-x" onClick={onClose} aria-label={S.close}>✕</button>
      </div>
      {doc.truncated && <div className="srs-note">⚠️ {S.truncated}</div>}
      <p className="srs-muted" style={{ margin: '10px 0 12px' }}>{S.intro}</p>

      <div className="srs-row-between" style={{ marginBottom: 8 }}>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          <button className="srs-link" onClick={() => setSelected(new Set(doc.sections.map(s => s.index)))}>{S.selectAll}</button>
          <button className="srs-link" onClick={() => setSelected(new Set(doc.sections.filter(s => s.suggested).map(s => s.index)))}>{S.selectSuggested}</button>
          <button className="srs-link" onClick={() => setSelected(new Set())}>{S.selectNone}</button>
        </div>
        <span className="srs-muted">{S.selected(selected.size, doc.sections.length)}{selected.size > 0 && ' · ' + S.estimate(selected.size)}</span>
      </div>

      <div className="srs-list">
        {doc.sections.map(s => {
          const isOpen = open === s.index;
          const content = edits[s.index] ?? s.content;
          return (
            <div key={s.index} className={'srs-item' + (selected.has(s.index) ? ' on' : '')}>
              <div className="srs-item-row">
                <label className="srs-check">
                  <input type="checkbox" checked={selected.has(s.index)} onChange={() => toggle(s.index)} />
                  <span className="srs-title" title={s.title}>{s.title}</span>
                </label>
                {!s.suggested && <span className="srs-tag srs-tag-gray">{S.notSuggested}</span>}
                <span className="srs-tag">{content.length.toLocaleString()}</span>
                <button className="srs-link" onClick={() => setOpen(isOpen ? null : s.index)}>{isOpen ? S.hide : S.view}</button>
              </div>
              {isOpen ? (
                <textarea className="srs-text" rows={8} value={content}
                  onChange={(e) => setEdits(prev => ({ ...prev, [s.index]: e.target.value }))} />
              ) : (
                <div className="srs-preview">{content.slice(0, 160).replace(/\s+/g, ' ')}{content.length > 160 ? '…' : ''}</div>
              )}
            </div>
          );
        })}
      </div>

      <div className="srs-actions">
        <button className="srs-btn" onClick={onClose}>{S.cancel}</button>
        <button className="app-btn app-btn-orange app-btn-lg" disabled={chosen.length === 0} onClick={() => onGenerate(chosen)}>
          {S.generate(chosen.length)}
        </button>
      </div>
    </div>
  );
}

const srsCss = `
.srs-card { background: #fff; border: 1px solid #e8ecf2; border-radius: 18px; padding: 18px; text-align: left; box-shadow: 0 10px 30px rgba(15,23,42,0.06); }
.srs-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
.srs-file { font-weight: 700; font-size: 16px; display: flex; gap: 8px; align-items: center; min-width: 0; }
.srs-file span { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.srs-meta { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; font-size: 13px; color: #64748b; margin-top: 4px; }
.srs-x { background: none; border: none; color: #94a3b8; font-size: 16px; cursor: pointer; padding: 4px; }
.srs-x:hover { color: #334155; }
.srs-muted { color: #64748b; font-size: 13.5px; }
.srs-note { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; border-radius: 10px; padding: 8px 12px; font-size: 13px; margin-top: 10px; }
.srs-row-between { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; }
.srs-link { background: none; border: none; padding: 2px 4px; color: #1f4fd8; font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit; white-space: nowrap; }
.srs-link:hover { text-decoration: underline; }
.srs-list { display: flex; flex-direction: column; gap: 6px; max-height: 380px; overflow-y: auto; padding-right: 2px; }
.srs-item { border: 1px solid #e8ecf2; border-radius: 12px; padding: 8px 10px; transition: border-color .15s, background .15s; }
.srs-item.on { border-color: #b9c8f5; background: #f8faff; }
.srs-item-row { display: flex; align-items: center; gap: 8px; }
.srs-check { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; cursor: pointer; }
.srs-check input { width: 16px; height: 16px; accent-color: #1f4fd8; flex-shrink: 0; }
.srs-title { font-weight: 600; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.srs-tag { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 10px; background: #eef3ff; color: #1f4fd8; white-space: nowrap; }
.srs-tag-gray { background: #f1f5f9; color: #64748b; font-weight: 600; }
.srs-preview { color: #64748b; font-size: 12.5px; margin: 4px 0 0 24px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.srs-text { width: 100%; box-sizing: border-box; margin-top: 8px; border: 1px solid #dbe1ea; border-radius: 10px; padding: 8px 10px; font-size: 13px; font-family: inherit; line-height: 1.5; outline: none; resize: vertical; }
.srs-text:focus { border-color: #1f4fd8; box-shadow: 0 0 0 3px rgba(31,79,216,0.1); }
.srs-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px; flex-wrap: wrap; }
.srs-btn { background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; padding: 9px 14px; font-weight: 600; font-size: 14px; cursor: pointer; font-family: inherit; color: #0f172a; }
.srs-btn:hover { background: #f8fafc; }
.srs-progress-label { display: flex; align-items: center; gap: 8px; margin: 14px 0 10px; font-size: 14px; flex-wrap: wrap; }
.srs-bar { height: 8px; background: #eef1f6; border-radius: 6px; overflow: hidden; margin-top: 10px; }
.srs-bar > div { height: 100%; background: linear-gradient(90deg, #1f4fd8, #7b4fe0); transition: width .4s ease; }
.srs-error-box { margin-top: 12px; background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; border-radius: 10px; padding: 10px 12px; font-size: 13px; }
.srs-error-box ul { margin: 6px 0 0; padding-left: 18px; }
`;
