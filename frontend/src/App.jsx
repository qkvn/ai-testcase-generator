import { useState, useEffect } from 'react';
import axios from 'axios';
import ExcelJS from 'exceljs';
import { saveAs } from 'file-saver';
import { jsPDF } from "jspdf";
import autoTable from "jspdf-autotable";
import TraceabilityPanel from './TraceabilityPanel';
import LandingPage from './LandingPage';
import { computeCoverage, parseRequirementIds } from './traceability';

axios.defaults.withCredentials = true;

// Xác định môi trường: Trống nếu chạy trên Render, dùng localhost nếu chạy dưới máy.
const API_BASE_URL = window.location.hostname === 'localhost' ? 'http://localhost:8080' : '';

const translations = {
  vi: {
    title: "Hệ Thống Tự Động Sinh Test Case",
    subtitle: "Đồ án tốt nghiệp - Spring Boot & Groq AI",
    inputLabel: "Nhập User Story / Requirement:",
    placeholder: "Nhập yêu cầu chức năng (VD: Chức năng đăng nhập...)",
    btnGenerate: "Sinh Test Case",
    btnLoading: "AI đang phân tích...",
    btnExport: "📥 Xuất Excel",
    btnSave: "💾 Lưu thay đổi",
    listTitle: "Danh Sách Test Cases",
    emptyList: "Chưa có test case nào cho phiên này.",
    colId: "Mã TC",
    colSummary: "Tóm tắt (Summary)",
    colPrecondition: "Điều kiện tiên quyết",
    colSteps: "Các bước (Steps)",
    colExpected: "Kết quả mong đợi",
    errEmpty: "Vui lòng nhập nội dung yêu cầu.",
    errConnect: "Không thể kết nối Backend: ",
    newChat: "Cuộc trò chuyện mới",
    search: "Tìm kiếm lịch sử...",
    history: "Lịch sử gần đây",
    checkingAuth: "Đang kiểm tra thông tin đăng nhập...",
    loginTitle: "Đăng Nhập Hệ Thống",
    loginBtn: "Đăng nhập bằng Google",
    logout: "Đăng xuất",
    exitShare: "← Thoát chế độ xem chung",
    saveSuccess: "Đã lưu các thay đổi thành công!",
    colReqs: "Yêu cầu",
    tabTestCases: "📋 Test Cases",
    tabTrace: "🔗 Truy vết & Độ bao phủ",
    traceAnalyze: "AI phân tích truy vết",
    traceAnalyzing: "AI đang phân tích...",
    traceAnalyzeConfirm: "AI sẽ phân tích lại danh sách yêu cầu và thay thế toàn bộ liên kết hiện tại. Tiếp tục?",
    traceUnsavedFirst: "Bạn có thay đổi chưa lưu. Hãy bấm \"Lưu thay đổi\" trước khi phân tích.",
    traceNoSession: "Không xác định được phiên hiện tại.",
    traceEmpty: "Phiên này chưa có danh sách yêu cầu. Hãy để AI phân tích User Story và test case để tạo ma trận truy vết.",
    traceAddReq: "Thêm yêu cầu",
    traceAddBtn: "Thêm",
    traceNewReqPlaceholder: "Mô tả yêu cầu mới...",
    traceCoverage: "Độ bao phủ yêu cầu",
    traceReqCovered: "yêu cầu đã được kiểm thử",
    traceUncovered: "Yêu cầu chưa bao phủ",
    traceOrphans: "Test case chưa gắn yêu cầu",
    traceUncoveredWarn: "Các yêu cầu chưa có test case:",
    traceOrphanWarn: "Test case chưa truy vết tới yêu cầu nào:",
    traceReqList: "Danh sách yêu cầu",
    traceColCode: "Mã YC",
    traceColDesc: "Mô tả yêu cầu",
    traceColPriority: "Ưu tiên",
    traceColLinked: "Test case liên kết",
    traceColStatus: "Trạng thái",
    traceCovered: "Đã bao phủ",
    traceNotCovered: "Chưa bao phủ",
    traceDelete: "Xóa yêu cầu",
    traceDeleteConfirm: "Xóa yêu cầu này và gỡ liên kết khỏi các test case?",
    traceMatrix: "Ma trận truy vết (RTM)",
    traceMatrixHint: "Nhấp vào ô để gắn / bỏ liên kết giữa yêu cầu và test case, sau đó bấm \"Lưu thay đổi\".",
    sheetReqs: "Yêu cầu",
    sheetMatrix: "Ma trận truy vết"
  },
  en: {
    title: "Automated Test Case Generator",
    subtitle: "Graduation Project - Spring Boot & Groq AI",
    inputLabel: "Enter User Story / Requirement:",
    placeholder: "Enter feature requirements...",
    btnGenerate: "Generate",
    btnLoading: "AI Analyzing...",
    btnExport: "📥 Export Excel",
    btnSave: "💾 Save Changes",
    listTitle: "Test Cases List",
    emptyList: "No test cases for this session yet.",
    colId: "TC ID",
    colSummary: "Summary",
    colPrecondition: "Precondition",
    colSteps: "Steps to Reproduce",
    colExpected: "Expected Result",
    errEmpty: "Please enter requirement.",
    errConnect: "Cannot connect to Backend: ",
    newChat: "New Chat",
    search: "Search history...",
    history: "Recent History",
    checkingAuth: "Checking authentication...",
    loginTitle: "System Login",
    loginBtn: "Login with Google",
    logout: "Logout",
    exitShare: "← Exit Shared View",
    saveSuccess: "Changes saved successfully!",
    colReqs: "Requirements",
    tabTestCases: "📋 Test Cases",
    tabTrace: "🔗 Traceability & Coverage",
    traceAnalyze: "AI traceability analysis",
    traceAnalyzing: "AI analyzing...",
    traceAnalyzeConfirm: "AI will re-analyze the requirements and replace all current links. Continue?",
    traceUnsavedFirst: "You have unsaved changes. Please click \"Save Changes\" before analyzing.",
    traceNoSession: "Cannot determine the current session.",
    traceEmpty: "This session has no requirements yet. Let AI analyze the user story and test cases to build the traceability matrix.",
    traceAddReq: "Add requirement",
    traceAddBtn: "Add",
    traceNewReqPlaceholder: "New requirement description...",
    traceCoverage: "Requirement coverage",
    traceReqCovered: "requirements tested",
    traceUncovered: "Uncovered requirements",
    traceOrphans: "Unlinked test cases",
    traceUncoveredWarn: "Requirements without test cases:",
    traceOrphanWarn: "Test cases not traced to any requirement:",
    traceReqList: "Requirements",
    traceColCode: "Req ID",
    traceColDesc: "Description",
    traceColPriority: "Priority",
    traceColLinked: "Linked test cases",
    traceColStatus: "Status",
    traceCovered: "Covered",
    traceNotCovered: "Not covered",
    traceDelete: "Delete requirement",
    traceDeleteConfirm: "Delete this requirement and unlink it from all test cases?",
    traceMatrix: "Requirement Traceability Matrix (RTM)",
    traceMatrixHint: "Click a cell to link / unlink a requirement and a test case, then click \"Save Changes\".",
    sheetReqs: "Requirements",
    sheetMatrix: "Traceability Matrix"
  }
};

function App() {
  const [userStory, setUserStory] = useState('');
  const [testCases, setTestCases] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [language, setLanguage] = useState('vi');

  const [showPlusMenu, setShowPlusMenu] = useState(false);
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');

  const [chatSessions, setChatSessions] = useState([]);
  const [activeSessionId, setActiveSessionId] = useState(null);

  const [activeMenuId, setActiveMenuId] = useState(null);
  const [editingSessionId, setEditingSessionId] = useState(null);
  const [editTitle, setEditTitle] = useState('');

  const [shareModalSession, setShareModalSession] = useState(null);
  const [isSharedView, setIsSharedView] = useState(false);

  const [editingCell, setEditingCell] = useState({ id: null, field: null });
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  const [currentUser, setCurrentUser] = useState(null);
  const [isAuthChecking, setIsAuthChecking] = useState(true);
  const [showSettingsMenu, setShowSettingsMenu] = useState(false);

  // Truy vết yêu cầu
  const [requirements, setRequirements] = useState([]);
  const [currentSessionId, setCurrentSessionId] = useState(null); // phiên đang hiển thị (kể cả phiên vừa sinh)
  const [activeTab, setActiveTab] = useState('testcases');
  const [traceBusy, setTraceBusy] = useState(false);

  const t = translations[language];

  useEffect(() => {
    const checkAuth = async () => {
      try {
        const res = await axios.get(API_BASE_URL + '/api/user/me');
        setCurrentUser(res.data);
        // Điền lại User Story người dùng đã nhập ở trang chủ trước khi đăng nhập
        try {
          const draft = sessionStorage.getItem('pendingUserStory');
          if (draft) { setUserStory(draft); sessionStorage.removeItem('pendingUserStory'); }
        } catch { /* sessionStorage không khả dụng */ }
      } catch (err) {
        setCurrentUser(null);
      } finally {
        setIsAuthChecking(false);
      }
    };
    checkAuth();
  }, []);

  const handleLogin = () => { window.location.href = API_BASE_URL + '/oauth2/authorization/google'; };
  const handleLogout = () => { window.location.href = API_BASE_URL + '/api/logout'; };

  const filteredHistory = chatSessions.filter(item =>
    item.title && item.title.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const fetchSessions = async () => {
    try {
      const res = await axios.get(API_BASE_URL + '/api/sessions');
      setChatSessions(res.data);
    } catch (err) {
      console.error('Lỗi khi tải lịch sử:', err);
    }
  };

  useEffect(() => {
    if (currentUser) {
      const params = new URLSearchParams(window.location.search);
      const sharedId = params.get('share');

      if (sharedId) {
        setIsSharedView(true);
        setIsSidebarOpen(false);
        fetchTestCasesOnly(Number(sharedId));
      } else {
        setIsSharedView(false);
        fetchSessions();
      }
    }
  }, [currentUser]);

  const handleNewChat = () => {
    setActiveSessionId(null);
    setCurrentSessionId(null);
    setTestCases([]);
    setRequirements([]);
    setActiveTab('testcases');
    setUserStory('');
    setError('');
    setHasUnsavedChanges(false);
    window.history.pushState({}, '', window.location.pathname);
  };

  const handleExitSharedView = () => {
    setIsSharedView(false);
    setIsSidebarOpen(true);
    window.history.pushState({}, '', window.location.pathname);
    handleNewChat();
    fetchSessions();
  };

  const fetchTestCasesOnly = async (sessionId) => {
    setActiveSessionId(sessionId);
    setCurrentSessionId(sessionId);
    setLoading(true);
    setError('');
    setHasUnsavedChanges(false);
    try {
      const [tcRes, reqRes] = await Promise.all([
        axios.get(API_BASE_URL + '/api/sessions/' + sessionId + '/testcases'),
        axios.get(API_BASE_URL + '/api/sessions/' + sessionId + '/requirements').catch(() => ({ data: [] }))
      ]);
      setTestCases(tcRes.data);
      setRequirements(reqRes.data || []);
    } catch (err) {
      setError("Lỗi tải test case: " + (err.response?.data?.message || err.message));
    } finally {
      setLoading(false);
    }
  };

  const handleSelectSession = (sessionId) => {
    fetchTestCasesOnly(sessionId);
  };

  const handleFakeUpload = () => {
    setShowPlusMenu(false);
    alert("Tính năng phân tích hình ảnh đang được phát triển. Sẽ ra mắt trong các phiên bản cập nhật tiếp theo!");
  };

  const handleGenerate = async () => {
    if (!userStory.trim()) {
      setError(t.errEmpty);
      return;
    }
    setError(''); setLoading(true);
    try {
      const res = await axios.post(API_BASE_URL + '/api/generate?lang=' + language, userStory, {
        headers: { 'Content-Type': 'text/plain' }
      });
      setTestCases(res.data.testCases || []);
      setRequirements(res.data.requirements || []);
      setCurrentSessionId(res.data.sessionId || null);
      setUserStory('');
      setHasUnsavedChanges(false);
      await fetchSessions();
      setActiveSessionId(null);
    } catch (err) {
      setError(t.errConnect + (err.response?.data?.message || err.message));
    } finally {
      setLoading(false);
    }
  };

  const handleStartRename = (session) => {
    setEditingSessionId(session.id);
    setEditTitle(session.title);
    setActiveMenuId(null);
  };

  const handleSaveRename = async (sessionId) => {
    if (!editTitle.trim()) { setEditingSessionId(null); return; }
    try {
      await axios.put(API_BASE_URL + '/api/sessions/' + sessionId + '/rename', editTitle, { headers: { 'Content-Type': 'text/plain' } });
      await fetchSessions();
      setEditingSessionId(null);
    } catch (err) {
      alert("Lỗi khi đổi tên: " + (err.response?.data?.message || err.message));
    }
  };

  const handleDeleteSession = async (sessionId) => {
    if (!window.confirm("Bạn có chắc chắn muốn xóa lịch sử này không?")) return;
    try {
      await axios.delete(API_BASE_URL + '/api/sessions/' + sessionId);
      await fetchSessions();
      if (activeSessionId === sessionId) handleNewChat();
      setActiveMenuId(null);
    } catch (err) {
      alert("Lỗi khi xóa: " + (err.response?.data?.message || err.message));
    }
  };

  const handleTogglePin = async (session) => {
    try {
      await axios.put(API_BASE_URL + '/api/sessions/' + session.id + '/pin');
      await fetchSessions();
      setActiveMenuId(null);
    } catch (err) {
      alert("Lỗi khi ghim: " + (err.response?.data?.message || err.message));
    }
  };

  const handleCopyLink = () => {
    const shareUrl = window.location.origin + '/?share=' + shareModalSession.id;
    navigator.clipboard.writeText(shareUrl).then(() => {
      alert("Đã sao chép đường liên kết vào khay nhớ tạm!");
    });
  };

  const handleDoubleClickCell = (id, field) => {
    if (isSharedView) return; 
    setEditingCell({ id, field });
  };

  const handleChangeCell = (id, field, value) => {
    setTestCases(prev => prev.map(tc => {
      if (tc.id === id) {
        let newValue = value;
        if (field === 'steps') newValue = value.split('\n').filter(s => s.trim() !== '');
        if (field === 'requirementIds') newValue = parseRequirementIds(value);
        return { ...tc, [field]: newValue };
      }
      return tc;
    }));
    setHasUnsavedChanges(true);
  };

  const handleSaveChangesDB = async () => {
      setLoading(true);
      try {
        await Promise.all(testCases.map(tc => {
          const targetId = tc.dbId ? tc.dbId : tc.id;
          return axios.put(API_BASE_URL + '/api/testcases/' + targetId, tc);
        }));
        setHasUnsavedChanges(false);
        alert(t.saveSuccess);
      } catch (err) {
        alert("Lỗi khi lưu dữ liệu: " + (err.response?.data?.message || err.message));
      } finally {
        setLoading(false);
      }
    };

  const renderEditableCell = (tc, field) => {
    const isEditing = editingCell.id === tc.id && editingCell.field === field;
    if (isEditing) {
      const val = field === 'steps' ? (tc[field] ? tc[field].join('\n') : '')
        : field === 'requirementIds' ? (tc[field] || []).join(', ') : tc[field];
      return (
        <textarea
          autoFocus
          defaultValue={val}
          onBlur={(e) => {
            handleChangeCell(tc.id, field, e.target.value);
            setEditingCell({ id: null, field: null });
          }}
          style={{ width: '100%', minHeight: '80px', padding: '8px', boxSizing: 'border-box', border: '2px solid #0d6efd', borderRadius: '6px', outline: 'none', fontSize: '14px', fontFamily: 'inherit' }}
        />
      );
    }

    if (field === 'requirementIds') {
      const ids = tc.requirementIds || [];
      return (
        <div onDoubleClick={() => handleDoubleClickCell(tc.id, field)} title={isSharedView ? '' : 'REQ-01, REQ-02'} style={{ minHeight: '30px', cursor: isSharedView ? 'default' : 'text', padding: '4px' }}>
          {ids.length ? ids.map(id => (
            <span key={id} style={{ display: 'inline-block', padding: '2px 8px', margin: '0 4px 4px 0', borderRadius: '10px', fontSize: '12px', fontWeight: 600, backgroundColor: '#e7f1ff', color: '#084298' }}>{id}</span>
          )) : <span style={{ color: '#adb5bd' }}>—</span>}
        </div>
      );
    }

    if (field === 'steps') {
      return (
        <div onDoubleClick={() => handleDoubleClickCell(tc.id, field)} style={{ minHeight: '30px', cursor: isSharedView ? 'default' : 'text', padding: '4px' }}>
          <ol style={{ paddingLeft: '16px', margin: 0 }}>{tc.steps && tc.steps.map((step, idx) => (<li key={idx} style={{ marginBottom: '4px' }}>{step}</li>))}</ol>
        </div>
      );
    }

    return (
      <div onDoubleClick={() => handleDoubleClickCell(tc.id, field)} style={{ minHeight: '30px', cursor: isSharedView ? 'default' : 'text', padding: '4px', whiteSpace: 'pre-wrap' }}>
        {tc[field]}
      </div>
    );
  };

  // ================== TRUY VẾT YÊU CẦU ==================
  const handleToggleLink = (tc, code) => {
    setTestCases(prev => prev.map(item => {
      if (item !== tc) return item;
      const ids = item.requirementIds || [];
      const next = ids.includes(code) ? ids.filter(c => c !== code) : [...ids, code];
      return { ...item, requirementIds: next };
    }));
    setHasUnsavedChanges(true);
  };

  const errMsg = (err) => {
    const d = err.response?.data;
    return (d && (d.message || (typeof d === 'string' ? d : ''))) || err.message;
  };

  const handleAnalyzeTraceability = async () => {
    if (!currentSessionId) { alert(t.traceNoSession); return; }
    if (hasUnsavedChanges) { alert(t.traceUnsavedFirst); return; }
    if (requirements.length > 0 && !window.confirm(t.traceAnalyzeConfirm)) return;
    setTraceBusy(true);
    try {
      await axios.post(API_BASE_URL + '/api/sessions/' + currentSessionId + '/traceability/analyze?lang=' + language);
      const [tcRes, reqRes] = await Promise.all([
        axios.get(API_BASE_URL + '/api/sessions/' + currentSessionId + '/testcases'),
        axios.get(API_BASE_URL + '/api/sessions/' + currentSessionId + '/requirements')
      ]);
      setTestCases(tcRes.data);
      setRequirements(reqRes.data);
    } catch (err) {
      alert(t.errConnect + errMsg(err));
    } finally {
      setTraceBusy(false);
    }
  };

  const handleAddRequirement = async (data) => {
    if (!currentSessionId) { alert(t.traceNoSession); return; }
    try {
      const res = await axios.post(API_BASE_URL + '/api/sessions/' + currentSessionId + '/requirements', data);
      setRequirements(prev => [...prev, res.data]);
    } catch (err) {
      alert(t.errConnect + errMsg(err));
    }
  };

  const handleUpdateRequirement = async (req, changes) => {
    setRequirements(prev => prev.map(r => r.dbId === req.dbId ? { ...r, ...changes } : r));
    try {
      await axios.put(API_BASE_URL + '/api/requirements/' + req.dbId, { ...req, ...changes });
    } catch (err) {
      setRequirements(prev => prev.map(r => r.dbId === req.dbId ? req : r));
      alert(t.errConnect + errMsg(err));
    }
  };

  const handleDeleteRequirement = async (req) => {
    if (!window.confirm(t.traceDeleteConfirm)) return;
    try {
      await axios.delete(API_BASE_URL + '/api/requirements/' + req.dbId);
      setRequirements(prev => prev.filter(r => r.dbId !== req.dbId));
      setTestCases(prev => prev.map(tc => ({ ...tc, requirementIds: (tc.requirementIds || []).filter(c => c !== req.code) })));
    } catch (err) {
      alert(t.errConnect + errMsg(err));
    }
  };

  const styleHeaderRow = (row) => {
    row.font = { bold: true, color: { argb: 'FFFFFFFF' }, size: 12 };
    row.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF0D6EFD' } };
    row.alignment = { vertical: 'middle', horizontal: 'center', wrapText: true };
    row.height = 30;
  };

  const addBorders = (worksheet) => {
    worksheet.eachRow((row) => {
      row.eachCell((cell) => {
        cell.border = { top: { style: 'thin' }, left: { style: 'thin' }, bottom: { style: 'thin' }, right: { style: 'thin' } };
      });
    });
  };

  const generateFormattedExcel = async (dataToExport, fileNameSuffix, reqs = []) => {
    if (!dataToExport || dataToExport.length === 0) { alert("Không có dữ liệu!"); return; }
    const workbook = new ExcelJS.Workbook();
    const worksheet = workbook.addWorksheet('Test Cases');
    worksheet.columns = [
      { header: t.colId, key: 'id', width: 12 }, { header: t.colSummary, key: 'summary', width: 40 },
      { header: t.colPrecondition, key: 'precondition', width: 35 }, { header: t.colSteps, key: 'steps', width: 55 },
      { header: t.colExpected, key: 'expected', width: 45 },
      { header: t.colReqs, key: 'reqs', width: 18 }
    ];
    const headerRow = worksheet.getRow(1);
    headerRow.font = { bold: true, color: { argb: 'FFFFFFFF' }, size: 12 };
    headerRow.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF0D6EFD' } };
    headerRow.alignment = { vertical: 'middle', horizontal: 'center', wrapText: true };
    headerRow.height = 30;

    dataToExport.forEach(tc => {
      const stepsText = tc.steps ? tc.steps.map((step, idx) => `${idx + 1}. ${step}`).join('\n') : '';
      worksheet.addRow({ id: tc.id, summary: tc.summary, precondition: tc.precondition, steps: stepsText, expected: tc.expected_result, reqs: (tc.requirementIds || []).join(', ') });
    });

    worksheet.eachRow((row, rowNumber) => {
      row.eachCell((cell) => {
        cell.border = { top: { style: 'thin' }, left: { style: 'thin' }, bottom: { style: 'thin' }, right: { style: 'thin' } };
        if (rowNumber > 1) cell.alignment = { vertical: 'top', wrapText: true };
      });
    });

    // Sheet Yêu cầu + Ma trận truy vết (nếu phiên có danh sách yêu cầu)
    if (reqs && reqs.length > 0) {
      const cov = computeCoverage(reqs, dataToExport);

      const reqSheet = workbook.addWorksheet(t.sheetReqs);
      reqSheet.columns = [
        { header: t.traceColCode, key: 'code', width: 12 }, { header: t.traceColDesc, key: 'desc', width: 60 },
        { header: t.traceColPriority, key: 'priority', width: 12 }, { header: t.traceColLinked, key: 'linked', width: 30 },
        { header: t.traceColStatus, key: 'status', width: 16 }
      ];
      styleHeaderRow(reqSheet.getRow(1));
      reqs.forEach(r => {
        const linked = cov.byReq[r.code] || [];
        const row = reqSheet.addRow({ code: r.code, desc: r.description, priority: r.priority, linked: linked.join(', '), status: linked.length ? t.traceCovered : t.traceNotCovered });
        row.alignment = { vertical: 'top', wrapText: true };
        if (!linked.length) row.getCell('status').font = { bold: true, color: { argb: 'FFDC3545' } };
      });
      addBorders(reqSheet);
      reqSheet.addRow({});
      const sumRow = reqSheet.addRow({ code: t.traceCoverage, desc: `${cov.percent}% (${cov.covered}/${cov.total})` });
      sumRow.font = { bold: true };

      const matrix = workbook.addWorksheet(t.sheetMatrix);
      matrix.columns = [
        { header: t.traceColCode + ' / TC', key: 'code', width: 16 },
        ...dataToExport.map((tc, i) => ({ header: tc.id, key: 'tc' + i, width: 10 })),
        { header: 'Σ', key: 'total', width: 8 }
      ];
      styleHeaderRow(matrix.getRow(1));
      reqs.forEach(r => {
        const rowData = { code: r.code, total: (cov.byReq[r.code] || []).length };
        dataToExport.forEach((tc, i) => { rowData['tc' + i] = (tc.requirementIds || []).includes(r.code) ? '✔' : ''; });
        const row = matrix.addRow(rowData);
        row.eachCell({ includeEmpty: true }, (cell, col) => {
          cell.alignment = { vertical: 'middle', horizontal: col === 1 ? 'left' : 'center' };
          if (cell.value === '✔') cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFD1E7DD' } };
        });
        if (!rowData.total) row.getCell('code').font = { bold: true, color: { argb: 'FFDC3545' } };
      });
      matrix.views = [{ state: 'frozen', xSplit: 1, ySplit: 1 }];
      addBorders(matrix);
    }

    const buffer = await workbook.xlsx.writeBuffer();
    const blob = new Blob([buffer], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    saveAs(blob, `TestCases_${fileNameSuffix}.xlsx`);
  };

  const handleMainExportExcel = () => generateFormattedExcel(testCases, "HienTai", requirements);

  const fetchSessionRequirements = async (sessionId) => {
    try {
      const res = await axios.get(API_BASE_URL + '/api/sessions/' + sessionId + '/requirements');
      return res.data || [];
    } catch { return []; }
  };

  const handleSidebarExportExcel = async (session) => {
    setActiveMenuId(null);
    try {
      const res = await axios.get(API_BASE_URL + '/api/sessions/' + session.id + '/testcases');
      let safeTitle = session.title.replace(/[\/\\?%*:|"<>]/g, '-').substring(0, 30);
      const reqs = await fetchSessionRequirements(session.id);
      generateFormattedExcel(res.data, safeTitle, reqs);
    } catch (err) { alert("Lỗi tải dữ liệu: " + (err.response?.data?.message || err.message)); }
  };

  const handleSidebarExportPDF = async (session) => {
    setActiveMenuId(null);
    try {
      const res = await axios.get(API_BASE_URL + '/api/sessions/' + session.id + '/testcases');
      const data = res.data;
      if (!data || data.length === 0) { alert("Không có dữ liệu để xuất PDF!"); return; }
      const doc = new jsPDF({ orientation: "landscape" });

      const loadFont = async (url, filename, fontName, fontStyle) => {
        try {
          const fontRes = await fetch(url);
          if (fontRes.ok) {
            const fontBuffer = await fontRes.arrayBuffer();
            const fontBytes = new Uint8Array(fontBuffer);
            let binary = '';
            for (let i = 0; i < fontBytes.byteLength; i++) { binary += String.fromCharCode(fontBytes[i]); }
            const base64Font = window.btoa(binary);
            doc.addFileToVFS(filename, base64Font);
            doc.addFont(filename, fontName, fontStyle);
          }
        } catch (fontErr) { console.error(`Lỗi tải font: `, fontErr); }
      };

      await loadFont('/arial.ttf', 'arial.ttf', 'Arial', 'normal');
      await loadFont('/arialbd.ttf', 'arialbd.ttf', 'Arial', 'bold');
      doc.setFont('Arial', 'normal');

      const tableColumn = [ t.colId, t.colSummary, t.colPrecondition, t.colSteps, t.colExpected, t.colReqs ];
      const tableRows = [];
      data.forEach(tc => {
        const stepsText = tc.steps ? tc.steps.map((step, idx) => `${idx + 1}. ${step}`).join('\n') : '';
        tableRows.push([ tc.id, tc.summary, tc.precondition, stepsText, tc.expected_result, (tc.requirementIds || []).join(', ') ]);
      });

      doc.setFont('Arial', 'bold');
      doc.text(`Danh sách Test Cases: ${session.title}`, 14, 15);
      autoTable(doc, {
        head: [tableColumn], body: tableRows, startY: 20,
        styles: { font: 'Arial', fontSize: 10, cellPadding: 3, overflow: 'linebreak' },
        headStyles: { fillColor: [13, 110, 253] },
        columnStyles: { 0: { cellWidth: 18 }, 1: { cellWidth: 50 }, 2: { cellWidth: 42 }, 3: { cellWidth: 74 }, 4: { cellWidth: 52 }, 5: { cellWidth: 32 } }
      });

      // Trang báo cáo truy vết & độ bao phủ
      const reqs = await fetchSessionRequirements(session.id);
      if (reqs.length > 0) {
        const cov = computeCoverage(reqs, data);
        doc.addPage();
        doc.setFont('Arial', 'bold');
        doc.text(`${t.traceMatrix} - ${t.traceCoverage}: ${cov.percent}% (${cov.covered}/${cov.total})`, 14, 15);
        autoTable(doc, {
          head: [[t.traceColCode, t.traceColDesc, t.traceColPriority, t.traceColLinked, t.traceColStatus]],
          body: reqs.map(r => {
            const linked = cov.byReq[r.code] || [];
            return [r.code, r.description, r.priority, linked.join(', '), linked.length ? t.traceCovered : t.traceNotCovered];
          }),
          startY: 20,
          styles: { font: 'Arial', fontSize: 10, cellPadding: 3, overflow: 'linebreak' },
          headStyles: { fillColor: [111, 66, 193] },
          columnStyles: { 0: { cellWidth: 22 }, 1: { cellWidth: 120 }, 2: { cellWidth: 22 }, 3: { cellWidth: 70 }, 4: { cellWidth: 34 } },
          didParseCell: (hook) => {
            if (hook.section === 'body' && hook.column.index === 4 && hook.cell.raw === t.traceNotCovered) {
              hook.cell.styles.textColor = [220, 53, 69];
            }
          }
        });
      }

      let safeTitle = session.title.replace(/[\/\\?%*:|"<>]/g, '-').substring(0, 30);
      doc.save(`TestCases_${safeTitle}.pdf`);
    } catch (err) { alert("Lỗi khi tải PDF: " + (err.response?.data?.message || err.message)); }
  };

  if (isAuthChecking) {
    return (
      <div style={{ display: 'flex', height: '100vh', width: '100vw', justifyContent: 'center', alignItems: 'center', backgroundColor: '#f8f9fa', fontFamily: 'system-ui, sans-serif' }}>
        <h3 style={{ color: '#6c757d' }}>{t.checkingAuth}</h3>
      </div>
    );
  }

  if (!currentUser) {
    // Trang chủ công khai: xem giới thiệu & test case nổi bật, mọi thao tác đều yêu cầu đăng nhập
    return <LandingPage language={language} setLanguage={setLanguage} onLogin={handleLogin} />;
  }

  return (
    <div style={{ display: 'flex', height: '100vh', width: '100vw', overflow: 'hidden', fontFamily: 'system-ui, sans-serif', backgroundColor: '#f8f9fa' }}>

      {activeMenuId && <div onClick={() => setActiveMenuId(null)} style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, zIndex: 9 }}></div>}
      {showSettingsMenu && <div onClick={() => setShowSettingsMenu(false)} style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, zIndex: 10 }}></div>}
      {showPlusMenu && <div onClick={() => setShowPlusMenu(false)} style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, zIndex: 8 }}></div>}

      {shareModalSession && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.6)', zIndex: 50, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          <div style={{ backgroundColor: '#1e1f22', color: '#e8eaed', padding: '24px', borderRadius: '16px', width: '550px', maxWidth: '90%', position: 'relative', boxShadow: '0 8px 24px rgba(0,0,0,0.2)' }}>
            <button onClick={() => setShareModalSession(null)} style={{ position: 'absolute', top: '16px', right: '16px', background: 'none', border: 'none', color: '#9aa0a6', fontSize: '24px', cursor: 'pointer', padding: '4px 8px' }}>✕</button>
            <h3 style={{ marginTop: 0, marginBottom: '24px', fontSize: '20px', fontWeight: 500, color: '#e8eaed' }}>Đường liên kết công khai có thể chia sẻ</h3>
            <div style={{ display: 'flex', backgroundColor: '#303134', borderRadius: '24px', padding: '6px 6px 6px 20px', alignItems: 'center', border: '1px solid #5f6368' }}>
              <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: '15px', color: '#e8eaed', userSelect: 'all' }}>
                {window.location.origin + '/?share=' + shareModalSession.id}
              </span>
              <button onClick={handleCopyLink} style={{ backgroundColor: '#8ab4f8', color: '#202124', border: 'none', borderRadius: '20px', padding: '10px 20px', fontSize: '14px', fontWeight: 600, cursor: 'pointer', marginLeft: '12px', display: 'flex', alignItems: 'center' }}>
                <span style={{ marginRight: '6px', fontSize: '16px' }}>⧉</span> Sao chép
              </button>
            </div>
            <p style={{ fontSize: '13px', color: '#9aa0a6', marginTop: '24px', lineHeight: '1.6', borderTop: '1px solid #3c4043', paddingTop: '16px' }}>
              ⓘ Các đường liên kết công khai có thể được chia sẻ lại. Vì vậy, hãy chia sẻ <span style={{color: '#8ab4f8'}}>một cách có trách nhiệm</span>, xoá bất cứ lúc nào. Nếu bạn chia sẻ với các bên thứ ba, thì chính sách của họ sẽ được áp dụng.
            </p>
          </div>
        </div>
      )}

      {!isSharedView && (
        <div style={{ width: isSidebarOpen ? '280px' : '0px', backgroundColor: '#e9ecef', transition: 'width 0.3s ease', overflow: 'hidden', display: 'flex', flexDirection: 'column', borderRight: isSidebarOpen ? '1px solid #dee2e6' : 'none', position: 'relative' }}>
          <div style={{ padding: '20px 20px 0 20px', minWidth: '280px', display: 'flex', flexDirection: 'column', flex: 1, overflow: 'hidden' }}>
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: '24px' }}>
              <button onClick={() => setIsSidebarOpen(false)} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', marginRight: '12px' }}>☰</button>
              <button onClick={handleNewChat} style={{ flex: 1, padding: '10px', backgroundColor: '#0d6efd', color: 'white', border: 'none', borderRadius: '20px', fontWeight: 500, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <span style={{ fontSize: '18px', marginRight: '8px' }}>+</span> {t.newChat}
              </button>
            </div>

            <div style={{ marginBottom: '20px' }}>
              <input type="text" placeholder={"🔍 " + t.search} value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} style={{ width: '100%', padding: '10px 12px', borderRadius: '8px', border: '1px solid #ced4da', boxSizing: 'border-box', outline: 'none' }} />
            </div>

            <h4 style={{ fontSize: '13px', color: '#6c757d', marginBottom: '12px', textTransform: 'uppercase' }}>{t.history}</h4>

            <div style={{ flex: 1, overflowY: 'auto', paddingRight: '4px', marginBottom: '16px' }}>
              {filteredHistory.map(item => (
                <div key={item.id} style={{ position: 'relative', display: 'flex', alignItems: 'center', padding: '10px 8px', borderRadius: '8px', marginBottom: '4px', backgroundColor: activeSessionId === item.id ? '#d1e7dd' : 'transparent' }}>
                  {editingSessionId === item.id ? (
                    <input type="text" value={editTitle} onChange={(e) => setEditTitle(e.target.value)} onBlur={() => handleSaveRename(item.id)} onKeyDown={(e) => { if (e.key === 'Enter') handleSaveRename(item.id); }} autoFocus style={{ flex: 1, padding: '4px 8px', borderRadius: '4px', border: '1px solid #0d6efd', outline: 'none', fontSize: '14px' }} onClick={(e) => e.stopPropagation()} />
                  ) : (
                    <div onClick={() => handleSelectSession(item.id)} style={{ flex: 1, cursor: 'pointer', fontSize: '14px', fontWeight: 500, color: '#212529', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {item.pinned === true || item.isPinned === true ? "📌 " : "💬 "} {item.title}
                    </div>
                  )}
                  <button onClick={(e) => { e.stopPropagation(); setActiveMenuId(activeMenuId === item.id ? null : item.id); }} style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: '16px', color: '#6c757d', padding: '0 8px' }}>⋮</button>

                  {activeMenuId === item.id && (
                    <div style={{ position: 'absolute', top: '36px', right: '10px', backgroundColor: '#fff', border: '1px solid #dee2e6', borderRadius: '8px', boxShadow: '0 4px 12px rgba(0,0,0,0.15)', zIndex: 10, width: '220px', padding: '8px 0' }}>
                      <div onClick={(e) => { e.stopPropagation(); setShareModalSession(item); setActiveMenuId(null); }} style={menuItemStyle}>🔗 Chia sẻ cuộc trò chuyện</div>
                      <div onClick={(e) => { e.stopPropagation(); handleTogglePin(item); }} style={menuItemStyle}>{item.pinned === true || item.isPinned === true ? "📍 Bỏ ghim" : "📌 Ghim"}</div>
                      <div onClick={(e) => { e.stopPropagation(); handleStartRename(item); }} style={menuItemStyle}>✏️ Đổi tên</div>
                      <div style={{ height: '1px', backgroundColor: '#e9ecef', margin: '4px 0' }}></div>
                      <div onClick={(e) => { e.stopPropagation(); handleSidebarExportPDF(item); }} style={menuItemStyle}>📄 Tải bản PDF xuống</div>
                      <div onClick={(e) => { e.stopPropagation(); handleSidebarExportExcel(item); }} style={menuItemStyle}>📊 Xuất sang Excel</div>
                      <div style={{ height: '1px', backgroundColor: '#e9ecef', margin: '4px 0' }}></div>
                      <div onClick={(e) => { e.stopPropagation(); handleDeleteSession(item.id); }} style={{ ...menuItemStyle, color: '#dc3545' }}>🗑️ Xóa</div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>

          <div style={{ padding: '16px', borderTop: '1px solid #dee2e6', display: 'flex', alignItems: 'center', backgroundColor: '#e9ecef', minWidth: '280px' }}>
            <img src={currentUser.picture || 'https://via.placeholder.com/40'} alt="Avatar" referrerPolicy="no-referrer" style={{ width: '40px', height: '40px', borderRadius: '50%', marginRight: '12px' }} />
            <div style={{ flex: 1, overflow: 'hidden' }}>
              <div style={{ fontWeight: 600, fontSize: '14px', color: '#212529', whiteSpace: 'nowrap', textOverflow: 'ellipsis', overflow: 'hidden' }}>{currentUser.name}</div>
              <div style={{ fontSize: '12px', color: '#6c757d', whiteSpace: 'nowrap', textOverflow: 'ellipsis', overflow: 'hidden' }}>{currentUser.email}</div>
            </div>
            <button onClick={() => setShowSettingsMenu(!showSettingsMenu)} style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: '20px' }}>⚙️</button>
            {showSettingsMenu && (
              <div style={{ position: 'absolute', bottom: '70px', right: '16px', backgroundColor: '#fff', border: '1px solid #dee2e6', borderRadius: '8px', boxShadow: '0 4px 12px rgba(0,0,0,0.15)', width: '200px', zIndex: 13, padding: '8px 0' }}>
                <div onClick={handleLogout} style={{ padding: '10px 16px', cursor: 'pointer', color: '#dc3545', fontSize: '14px', fontWeight: 500 }}>🚪 {t.logout}</div>
              </div>
            )}
          </div>
        </div>
      )}

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', height: '100vh', overflowY: 'auto', backgroundColor: '#ffffff' }}>
        <div style={{ padding: '16px 24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottom: '1px solid #f1f3f5', position: 'sticky', top: 0, backgroundColor: '#fff', zIndex: 5 }}>
          <div style={{ display: 'flex', alignItems: 'center' }}>
            {!isSharedView && !isSidebarOpen && (<button onClick={() => setIsSidebarOpen(true)} style={{ background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', marginRight: '16px' }}>☰</button>)}
            {isSharedView && (
              <button onClick={handleExitSharedView} style={{ background: 'none', border: 'none', fontSize: '14px', fontWeight: 600, color: '#0d6efd', cursor: 'pointer', marginRight: '16px', padding: '6px 12px', borderRadius: '20px', backgroundColor: '#e9ecef' }}>
                {t.exitShare}
              </button>
            )}
            <span style={{ fontSize: '20px', fontWeight: 'bold', color: '#212529' }}>{t.title}</span>
          </div>
          <select value={language} onChange={(e) => { setLanguage(e.target.value); setError(''); }} style={{ padding: '6px 12px', borderRadius: '6px', border: '1px solid #ced4da', fontWeight: 500, cursor: 'pointer' }}>
            <option value="vi">🇻🇳 Tiếng Việt</option>
            <option value="en">🇺🇸 English</option>
          </select>
        </div>

        <div style={{ maxWidth: '900px', width: '100%', margin: '0 auto', padding: '32px 16px' }}>
          {!activeSessionId && !isSharedView && (
            <div style={{ marginBottom: '32px', position: 'relative' }}>
              <label style={{ fontWeight: 600, display: 'block', marginBottom: '8px' }}>{t.inputLabel}</label>
              <div style={{ border: '1px solid #ced4da', borderRadius: '16px', padding: '12px', backgroundColor: '#fff', boxShadow: '0 2px 6px rgba(0,0,0,0.05)' }}>
                <textarea rows={3} style={{ width: '100%', border: 'none', outline: 'none', fontSize: '15px', resize: 'none', boxSizing: 'border-box' }} placeholder={t.placeholder} value={userStory} onChange={(e) => setUserStory(e.target.value)} />
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '8px', borderTop: '1px solid #f1f3f5', paddingTop: '8px' }}>
                  <div style={{ position: 'relative' }}>
                    <button onClick={() => setShowPlusMenu(!showPlusMenu)} style={{ width: '36px', height: '36px', borderRadius: '50%', border: '1px solid #ced4da', backgroundColor: '#f8f9fa', fontSize: '20px', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>+</button>
                    {showPlusMenu && (
                      <div style={{ position: 'absolute', bottom: '46px', left: 0, backgroundColor: '#fff', border: '1px solid #dee2e6', borderRadius: '12px', boxShadow: '0 4px 12px rgba(0,0,0,0.15)', width: '180px', zIndex: 10, padding: '6px 0' }}>
                        <div onClick={handleFakeUpload} style={{ padding: '10px 16px', fontSize: '14px', cursor: 'pointer', display: 'flex', alignItems: 'center', color: '#212529' }} onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#f8f9fa'} onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}>
                          📁 Tải tệp lên...
                        </div>
                      </div>
                    )}
                  </div>
                  <button onClick={handleGenerate} disabled={loading} style={{ padding: '10px 24px', backgroundColor: loading ? '#6c757d' : '#0d6efd', color: '#fff', border: 'none', borderRadius: '20px', fontWeight: 600, fontSize: '14px', cursor: loading ? 'not-allowed' : 'pointer' }}>
                    {loading ? t.btnLoading : "✨ " + t.btnGenerate}
                  </button>
                </div>
              </div>
              {error && <p style={{ color: '#dc3545', marginTop: '8px', fontSize: '14px' }}>{error}</p>}
            </div>
          )}

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
              <h2 style={{ margin: 0, fontSize: '20px' }}>{t.listTitle} ({testCases.length})</h2>
              {!isSharedView && activeTab === 'testcases' && testCases.length > 0 && <span style={{ fontSize: '13px', color: '#6c757d' }}>(Click đúp vào ô để sửa)</span>}
            </div>
            <div style={{ display: 'flex', gap: '12px' }}>
              {hasUnsavedChanges && (
                <button onClick={handleSaveChangesDB} disabled={loading} style={{ padding: '8px 16px', backgroundColor: '#0dcaf0', color: '#000', border: 'none', borderRadius: '6px', fontWeight: 600, cursor: loading ? 'not-allowed' : 'pointer' }}>
                  {t.btnSave}
                </button>
              )}
              {testCases.length > 0 && (
                <button onClick={handleMainExportExcel} style={{ padding: '8px 16px', backgroundColor: '#198754', color: '#fff', border: 'none', borderRadius: '6px', fontWeight: 500, cursor: 'pointer' }}>
                  {t.btnExport}
                </button>
              )}
            </div>
          </div>

          {testCases.length > 0 && (
            <div style={{ display: 'flex', gap: '4px', borderBottom: '2px solid #e9ecef', marginBottom: '16px' }}>
              {[['testcases', t.tabTestCases], ['trace', t.tabTrace]].map(([key, label]) => {
                const cov = key === 'trace' && requirements.length > 0 ? computeCoverage(requirements, testCases) : null;
                return (
                  <button key={key} onClick={() => setActiveTab(key)} style={{ padding: '10px 16px', background: 'none', border: 'none', borderBottom: activeTab === key ? '3px solid #0d6efd' : '3px solid transparent', marginBottom: '-2px', color: activeTab === key ? '#0d6efd' : '#6c757d', fontWeight: 600, fontSize: '14px', cursor: 'pointer' }}>
                    {label}{cov && <span style={{ marginLeft: '6px', padding: '1px 8px', borderRadius: '10px', fontSize: '12px', backgroundColor: cov.percent >= 80 ? '#d1e7dd' : cov.percent >= 50 ? '#ffe5d0' : '#f8d7da', color: '#212529' }}>{cov.percent}%</span>}
                  </button>
                );
              })}
            </div>
          )}

          {testCases.length > 0 && activeTab === 'trace' ? (
            <TraceabilityPanel
              t={t}
              requirements={requirements}
              testCases={testCases}
              readOnly={isSharedView}
              busy={traceBusy}
              onToggleLink={handleToggleLink}
              onAnalyze={handleAnalyzeTraceability}
              onAddRequirement={handleAddRequirement}
              onUpdateRequirement={handleUpdateRequirement}
              onDeleteRequirement={handleDeleteRequirement}
            />
          ) : testCases.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '40px', backgroundColor: '#f8f9fa', borderRadius: '12px', border: '1px dashed #ced4da', color: '#6c757d' }}>{t.emptyList}</div>
          ) : (
            <div style={{ overflowX: 'auto', borderRadius: '8px', border: '1px solid #dee2e6' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: '14px' }}>
                <thead>
                  <tr style={{ backgroundColor: '#f8f9fa', borderBottom: '2px solid #dee2e6' }}>
                    <th style={{ padding: '12px', width: '80px' }}>{t.colId}</th>
                    <th style={{ padding: '12px', width: '25%' }}>{t.colSummary}</th>
                    <th style={{ padding: '12px', width: '25%' }}>{t.colPrecondition}</th>
                    <th style={{ padding: '12px' }}>{t.colSteps}</th>
                    <th style={{ padding: '12px', width: '25%' }}>{t.colExpected}</th>
                    <th style={{ padding: '12px', width: '110px' }}>{t.colReqs}</th>
                  </tr>
                </thead>
                <tbody>
                  {testCases.map((tc, index) => (
                    <tr key={tc.dbId || index} style={{ borderBottom: '1px solid #e9ecef', verticalAlign: 'top' }}>
                      <td style={{ padding: '12px', fontWeight: 600, color: '#495057' }}>{tc.id}</td>
                      <td style={{ padding: '8px' }}>{renderEditableCell(tc, 'summary')}</td>
                      <td style={{ padding: '8px' }}>{renderEditableCell(tc, 'precondition')}</td>
                      <td style={{ padding: '8px' }}>{renderEditableCell(tc, 'steps')}</td>
                      <td style={{ padding: '8px' }}>{renderEditableCell(tc, 'expected_result')}</td>
                      <td style={{ padding: '8px' }}>{renderEditableCell(tc, 'requirementIds')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

const menuItemStyle = { padding: '10px 16px', fontSize: '14px', cursor: 'pointer', color: '#212529', transition: 'background 0.2s' };
export default App;