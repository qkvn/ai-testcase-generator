import { useState } from 'react';
import { computeCoverage, coverageColor, priorityStyle } from './traceability';

// Bảng "Truy vết yêu cầu & Độ bao phủ":
//  - Thẻ tổng quan độ bao phủ
//  - Danh sách yêu cầu (thêm / sửa / xóa)
//  - Ma trận truy vết Yêu cầu × Test Case (nhấp để gắn / bỏ liên kết)
export default function TraceabilityPanel({
  t, requirements, testCases, readOnly, busy,
  onToggleLink, onAnalyze, onAddRequirement, onUpdateRequirement, onDeleteRequirement,
}) {
  const [editingReqId, setEditingReqId] = useState(null);
  const [newDesc, setNewDesc] = useState('');
  const [newPriority, setNewPriority] = useState('Medium');
  const [showAdd, setShowAdd] = useState(false);

  const cov = computeCoverage(requirements, testCases);
  const color = coverageColor(cov.percent);

  const submitNew = async () => {
    if (!newDesc.trim()) return;
    await onAddRequirement({ description: newDesc.trim(), priority: newPriority });
    setNewDesc(''); setNewPriority('Medium'); setShowAdd(false);
  };

  const actionBar = !readOnly && (
    <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
      <button onClick={onAnalyze} disabled={busy} style={btn(busy ? '#6c757d' : '#7b4fe0')}>
        {busy ? t.traceAnalyzing : '🤖 ' + t.traceAnalyze}
      </button>
      {requirements.length > 0 && (
        <button onClick={() => setShowAdd(v => !v)} disabled={busy} style={btn('#1f4fd8')}>+ {t.traceAddReq}</button>
      )}
    </div>
  );

  if (requirements.length === 0) {
    return (
      <div style={{ textAlign: 'center', padding: '40px 24px', backgroundColor: '#f8faff', borderRadius: '14px', border: '2px dashed #dbe4ff', color: '#6c757d' }}>
        <div style={{ fontSize: '32px', marginBottom: '8px' }}>🔗</div>
        <p style={{ margin: '0 0 16px 0' }}>{testCases.length === 0 ? t.emptyList : t.traceEmpty}</p>
        {testCases.length > 0 && <div style={{ display: 'flex', justifyContent: 'center' }}>{actionBar}</div>}
      </div>
    );
  }

  return (
    <div>
      {/* ---------- Tổng quan ---------- */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '12px', marginBottom: '20px' }}>
        <div style={{ ...card, gridColumn: 'span 2', minWidth: 0 }}>
          <div style={cardLabel}>{t.traceCoverage}</div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: '8px' }}>
            <span style={{ fontSize: '32px', fontWeight: 700, color }}>{cov.percent}%</span>
            <span style={{ color: '#6c757d', fontSize: '14px' }}>{cov.covered}/{cov.total} {t.traceReqCovered}</span>
          </div>
          <div style={{ height: '10px', backgroundColor: '#e9ecef', borderRadius: '5px', overflow: 'hidden', marginTop: '8px' }}>
            <div style={{ width: cov.percent + '%', height: '100%', backgroundColor: color, transition: 'width 0.4s ease' }} />
          </div>
        </div>
        <div style={card}>
          <div style={cardLabel}>{t.traceUncovered}</div>
          <div style={{ fontSize: '28px', fontWeight: 700, color: cov.uncovered.length ? '#dc3545' : '#198754' }}>{cov.uncovered.length}</div>
        </div>
        <div style={card}>
          <div style={cardLabel}>{t.traceOrphans}</div>
          <div style={{ fontSize: '28px', fontWeight: 700, color: cov.orphans.length ? '#fd7e14' : '#198754' }}>{cov.orphans.length}</div>
        </div>
      </div>

      {cov.uncovered.length > 0 && (
        <div style={{ ...alertBox, backgroundColor: '#f8d7da', color: '#842029', borderColor: '#f5c2c7' }}>
          ⚠️ <b>{t.traceUncoveredWarn}</b> {cov.uncovered.join(', ')}
        </div>
      )}
      {cov.orphans.length > 0 && (
        <div style={{ ...alertBox, backgroundColor: '#fff3cd', color: '#664d03', borderColor: '#ffecb5' }}>
          ℹ️ <b>{t.traceOrphanWarn}</b> {cov.orphans.join(', ')}
        </div>
      )}

      {/* ---------- Danh sách yêu cầu ---------- */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '24px 0 12px', gap: '12px', flexWrap: 'wrap' }}>
        <h3 style={{ margin: 0, fontSize: '17px' }}>{t.traceReqList} ({requirements.length})</h3>
        {actionBar}
      </div>

      {showAdd && !readOnly && (
        <div style={{ display: 'flex', gap: '8px', marginBottom: '12px', flexWrap: 'wrap' }}>
          <input autoFocus value={newDesc} onChange={e => setNewDesc(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') submitNew(); }}
            placeholder={t.traceNewReqPlaceholder} style={{ flex: 1, minWidth: '200px', padding: '8px 12px', borderRadius: '6px', border: '1px solid #ced4da', outline: 'none' }} />
          <select value={newPriority} onChange={e => setNewPriority(e.target.value)} style={{ padding: '8px', borderRadius: '6px', border: '1px solid #ced4da' }}>
            <option value="High">High</option><option value="Medium">Medium</option><option value="Low">Low</option>
          </select>
          <button onClick={submitNew} style={btn('#198754')}>{t.traceAddBtn}</button>
        </div>
      )}

      <div style={{ overflowX: 'auto', borderRadius: '12px', border: '1px solid #e6ebf3' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '14px', textAlign: 'left' }}>
          <thead>
            <tr style={{ backgroundColor: '#eef3ff', color: '#1f4fd8', borderBottom: '1px solid #dbe4ff' }}>
              <th style={th}>{t.traceColCode}</th>
              <th style={th}>{t.traceColDesc}</th>
              <th style={th}>{t.traceColPriority}</th>
              <th style={th}>{t.traceColLinked}</th>
              <th style={th}>{t.traceColStatus}</th>
              {!readOnly && <th style={th}></th>}
            </tr>
          </thead>
          <tbody>
            {requirements.map(r => {
              const linked = cov.byReq[r.code] || [];
              return (
                <tr key={r.dbId || r.code} style={{ borderBottom: '1px solid #e9ecef', verticalAlign: 'top', backgroundColor: linked.length ? 'transparent' : '#fff5f5' }}>
                  <td style={{ ...td, fontWeight: 600, whiteSpace: 'nowrap' }}>{r.code}</td>
                  <td style={td}>
                    {editingReqId === r.dbId ? (
                      <textarea autoFocus defaultValue={r.description}
                        onBlur={e => { setEditingReqId(null); if (e.target.value !== r.description) onUpdateRequirement(r, { description: e.target.value }); }}
                        style={{ width: '100%', minHeight: '60px', padding: '6px', boxSizing: 'border-box', border: '2px solid #1f4fd8', borderRadius: '8px', outline: 'none', fontFamily: 'inherit', fontSize: '14px' }} />
                    ) : (
                      <div onDoubleClick={() => !readOnly && setEditingReqId(r.dbId)} style={{ whiteSpace: 'pre-wrap', cursor: readOnly ? 'default' : 'text' }}>{r.description}</div>
                    )}
                  </td>
                  <td style={td}>
                    {readOnly ? (
                      <span style={{ ...badge, ...(priorityStyle[r.priority] || priorityStyle.Medium) }}>{r.priority}</span>
                    ) : (
                      <select value={r.priority || 'Medium'} onChange={e => onUpdateRequirement(r, { priority: e.target.value })}
                        style={{ ...badge, ...(priorityStyle[r.priority] || priorityStyle.Medium), border: 'none', cursor: 'pointer' }}>
                        <option value="High">High</option><option value="Medium">Medium</option><option value="Low">Low</option>
                      </select>
                    )}
                  </td>
                  <td style={td}>
                    {linked.length ? linked.map(id => <span key={id} style={{ ...badge, backgroundColor: '#e7f1ff', color: '#084298', marginRight: '4px', marginBottom: '4px', display: 'inline-block' }}>{id}</span>) : <span style={{ color: '#adb5bd' }}>—</span>}
                  </td>
                  <td style={{ ...td, whiteSpace: 'nowrap' }}>
                    {linked.length
                      ? <span style={{ ...badge, backgroundColor: '#d1e7dd', color: '#0f5132' }}>✔ {t.traceCovered}</span>
                      : <span style={{ ...badge, backgroundColor: '#f8d7da', color: '#842029' }}>✘ {t.traceNotCovered}</span>}
                  </td>
                  {!readOnly && (
                    <td style={td}>
                      <button onClick={() => onDeleteRequirement(r)} title={t.traceDelete} style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: '15px' }}>🗑️</button>
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* ---------- Ma trận truy vết ---------- */}
      <h3 style={{ margin: '28px 0 4px', fontSize: '17px' }}>{t.traceMatrix}</h3>
      {!readOnly && <p style={{ margin: '0 0 12px', fontSize: '13px', color: '#6c757d' }}>{t.traceMatrixHint}</p>}
      <div style={{ overflowX: 'auto', borderRadius: '12px', border: '1px solid #e6ebf3', marginBottom: '24px' }}>
        <table style={{ borderCollapse: 'collapse', fontSize: '13px', minWidth: '100%' }}>
          <thead>
            <tr style={{ backgroundColor: '#eef3ff', color: '#1f4fd8' }}>
              <th style={{ ...mth, position: 'sticky', left: 0, backgroundColor: '#eef3ff', zIndex: 1, textAlign: 'left', minWidth: '90px' }}>{t.traceColCode} \ TC</th>
              {testCases.map((tc, i) => <th key={tc.dbId || i} title={tc.summary} style={mth}>{tc.id}</th>)}
              <th style={{ ...mth, backgroundColor: '#e9ecef' }}>Σ</th>
            </tr>
          </thead>
          <tbody>
            {requirements.map(r => {
              const linked = cov.byReq[r.code] || [];
              return (
                <tr key={r.dbId || r.code}>
                  <td title={r.description} style={{ ...mtd, position: 'sticky', left: 0, backgroundColor: linked.length ? '#fff' : '#fff5f5', fontWeight: 600, textAlign: 'left', zIndex: 1, color: linked.length ? '#212529' : '#dc3545' }}>{r.code}</td>
                  {testCases.map((tc, i) => {
                    const on = (tc.requirementIds || []).includes(r.code);
                    return (
                      <td key={tc.dbId || i}
                        onClick={() => !readOnly && onToggleLink(tc, r.code)}
                        title={`${r.code} ↔ ${tc.id}`}
                        style={{ ...mtd, cursor: readOnly ? 'default' : 'pointer', backgroundColor: on ? '#d1e7dd' : 'transparent', color: '#0f5132', fontWeight: 700 }}>
                        {on ? '✔' : ''}
                      </td>
                    );
                  })}
                  <td style={{ ...mtd, backgroundColor: '#f8f9fa', fontWeight: 600, color: linked.length ? '#212529' : '#dc3545' }}>{linked.length}</td>
                </tr>
              );
            })}
            <tr style={{ backgroundColor: '#eef3ff', color: '#1f4fd8' }}>
              <td style={{ ...mtd, position: 'sticky', left: 0, backgroundColor: '#e9ecef', fontWeight: 600, textAlign: 'left', zIndex: 1 }}>Σ</td>
              {testCases.map((tc, i) => {
                const n = (tc.requirementIds || []).filter(c => cov.byReq[c]).length;
                return <td key={tc.dbId || i} style={{ ...mtd, fontWeight: 600, color: n ? '#212529' : '#fd7e14' }}>{n}</td>;
              })}
              <td style={{ ...mtd, backgroundColor: '#e9ecef' }}></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}

const btn = (bg) => ({ padding: '8px 14px', backgroundColor: bg, color: '#fff', border: 'none', borderRadius: '10px', fontWeight: 600, boxShadow: '0 4px 12px rgba(31,79,216,0.18)', fontSize: '14px', cursor: 'pointer' });
const card = { padding: '16px', borderRadius: '14px', border: '1px solid #e6ebf3', backgroundColor: '#f8faff' };
const cardLabel = { fontSize: '12px', color: '#6c757d', textTransform: 'uppercase', fontWeight: 600, marginBottom: '6px' };
const alertBox = { padding: '10px 14px', borderRadius: '8px', border: '1px solid', fontSize: '14px', marginBottom: '8px' };
const th = { padding: '10px 12px', whiteSpace: 'nowrap' };
const td = { padding: '10px 12px' };
const badge = { padding: '2px 8px', borderRadius: '10px', fontSize: '12px', fontWeight: 600 };
const mth = { padding: '8px 10px', borderBottom: '2px solid #dee2e6', borderRight: '1px solid #e9ecef', whiteSpace: 'nowrap', textAlign: 'center' };
const mtd = { padding: '6px 10px', borderBottom: '1px solid #e9ecef', borderRight: '1px solid #e9ecef', textAlign: 'center', minWidth: '44px' };
