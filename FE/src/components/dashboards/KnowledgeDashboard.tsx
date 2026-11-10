import React, { useCallback, useEffect, useRef, useState } from 'react';
import { BookOpen, RefreshCw, Search, Trash2, UploadCloud } from 'lucide-react';
import { blueAiApi, BlueAiApiError, type DocumentResponse } from '../../api/blueAiApi';
import { useAppStore } from '../../store/useAppStore';
import { Badge } from '../ui/Badge';
import { Button } from '../ui/Button';
import { Card } from '../ui/Card';
import { Modal } from '../ui/Modal';

export const KnowledgeDashboard: React.FC = () => {
  const { showToast } = useAppStore();
  const [documents, setDocuments] = useState<DocumentResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<DocumentResponse | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState('');
  const deletingRequest = useRef(false);
  const listRequest = useRef(0);
  const fileInput = useRef<HTMLInputElement>(null);

  const loadDocuments = useCallback(async () => {
    const request = ++listRequest.current;
    setLoading(true);
    try {
      const result = await blueAiApi.listDocuments();
      if (request === listRequest.current) setDocuments(result);
    } catch (error) {
      if (request === listRequest.current) {
        showToast(error instanceof Error ? error.message : 'Không thể tải danh sách tài liệu.', 'warning');
      }
    } finally {
      if (request === listRequest.current) setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    void loadDocuments();
    return () => { listRequest.current++; };
  }, [loadDocuments]);

  const deleteDocument = async () => {
    if (!deleteTarget || deletingRequest.current) return;
    deletingRequest.current = true;
    setDeleting(true);
    setDeleteError('');
    try {
      try {
        await blueAiApi.deleteDocument(deleteTarget.id);
      } catch (error) {
        // Another admin or the retention worker may have already removed it.
        if (!(error instanceof BlueAiApiError) || error.status !== 404) throw error;
      }
      // Ignore an older list response that could otherwise restore the deleted row.
      listRequest.current++;
      setDocuments(current => current.filter(document => document.id !== deleteTarget.id));
      setDeleteTarget(null);
      showToast('Tài liệu đã được gỡ khỏi kho tri thức.', 'success');
      await loadDocuments();
    } catch (error) {
      setDeleteError(error instanceof Error ? error.message : 'Không thể xóa tài liệu. Vui lòng thử lại.');
    } finally {
      deletingRequest.current = false;
      setDeleting(false);
    }
  };

  const upload = async (file?: File) => {
    if (!file) return;
    try {
      const uploaded = await blueAiApi.uploadDocument(file);
      if (uploaded.ingestionTriggered === false) {
        showToast('Đã tải lên nhưng chưa gọi được RAG service để chunk ngay. Tài liệu sẽ chờ worker quét lại.', 'warning');
      } else {
        showToast('Đã tải tài liệu lên, RAG service đang chunk ngay.', 'success');
      }
      await loadDocuments();
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Tải tài liệu thất bại.', 'warning');
    } finally {
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const filtered = documents.filter(document => document.originalFilename.toLowerCase().includes(search.toLowerCase()) || document.id.includes(search));
  const totalChunks = documents.reduce((sum, document) => sum + (document.producedChunkCount || 0), 0);

  return (
    <div className="flex-1 overflow-y-auto p-6 sm:p-8 space-y-6 bg-canvas-light dark:bg-canvas-dark">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold flex items-center gap-2"><BookOpen className="w-6 h-6 text-cobalt-600" />Quản lý Tài liệu RAG</h1>
          <p className="text-sm text-slate-500 mt-1">Kho tri thức dùng chung cho các Admin và các trợ lý BlueAI.</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" disabled={deleting} onClick={() => void loadDocuments()}><RefreshCw className="w-4 h-4 mr-1" />Làm mới</Button>
          <input ref={fileInput} type="file" accept=".pdf,.docx,.csv,.txt,.md" className="hidden" onChange={event => void upload(event.target.files?.[0])} />
          <Button variant="primary" size="sm" disabled={deleting} onClick={() => fileInput.current?.click()}><UploadCloud className="w-4 h-4 mr-1" />Tải tài liệu</Button>
        </div>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Card className="p-4"><p className="text-xs text-slate-500">Tổng tài liệu</p><p className="text-2xl font-bold">{documents.length}</p></Card>
        <Card className="p-4"><p className="text-xs text-slate-500">Sẵn sàng</p><p className="text-2xl font-bold text-emerald-600">{documents.filter(d => d.status === 'READY').length}</p></Card>
        <Card className="p-4"><p className="text-xs text-slate-500">Đang xử lý</p><p className="text-2xl font-bold text-amber-600">{documents.filter(d => d.status === 'PROCESSING' || d.status === 'PENDING_CHUNKING').length}</p></Card>
        <Card className="p-4"><p className="text-xs text-slate-500">Chunks đã tạo</p><p className="text-2xl font-bold font-mono">{totalChunks}</p></Card>
      </div>

      <div className="relative max-w-md"><Search className="w-4 h-4 absolute left-3 top-2.5 text-slate-400" /><input value={search} onChange={event => setSearch(event.target.value)} placeholder="Tìm tên hoặc ID tài liệu" className="w-full pl-9 pr-3 py-2 text-sm rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900" /></div>

      <Card className="overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 dark:bg-slate-900 border-b"><tr><th className="p-4">Tài liệu</th><th className="p-4">Loại</th><th className="p-4">Dung lượng</th><th className="p-4">Chunks</th><th className="p-4">Trạng thái</th><th className="p-4">Thao tác</th></tr></thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {filtered.map(document => (
                <tr key={document.id}>
                  <td className="p-4"><p className="font-semibold">{document.originalFilename}</p><p className="text-xs text-slate-400 font-mono">{document.id}</p></td>
                  <td className="p-4">{document.contentType}</td>
                  <td className="p-4">{(document.sizeBytes / 1024).toFixed(1)} KB</td>
                  <td className="p-4">{document.producedChunkCount ?? '—'}</td>
                  <td className="p-4"><Badge variant={document.status === 'READY' ? 'confirmed' : document.status === 'FAILED' ? 'danger' : 'inferred'} size="sm">{document.status}</Badge></td>
                  <td className="p-4">
                    <Button variant="outline" size="sm" disabled={loading || deleting}
                      aria-label={`Xóa tài liệu ${document.originalFilename}`}
                      className="text-rose-600 hover:text-rose-700 dark:text-rose-400"
                      onClick={() => { setDeleteError(''); setDeleteTarget(document); }}>
                      <Trash2 className="h-4 w-4" aria-hidden="true" />Xóa
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && filtered.length === 0 && <p className="p-8 text-center text-sm text-slate-500">Chưa có tài liệu phù hợp.</p>}
          {loading && <p className="p-8 text-center text-sm text-slate-500">Đang tải dữ liệu…</p>}
        </div>
      </Card>
      {deleteTarget && <Modal title="Xóa tài liệu khỏi kho tri thức" busy={deleting}
        onClose={() => { setDeleteTarget(null); setDeleteError(''); }}>
        <p className="break-words font-semibold">{deleteTarget.originalFilename}</p>
        <p className="mt-3 text-sm leading-6 text-slate-600 dark:text-slate-300">
          Tài liệu sẽ bị gỡ khỏi kho tri thức dùng chung và ngừng được dùng cho các lần tra cứu mới.
          Thao tác này ảnh hưởng mọi Admin và các bot đang sử dụng tài liệu.
          Các câu trả lời đã tạo có thể vẫn chứa nội dung từ tài liệu này.
        </p>
        {deleteError && <p role="alert" className="mt-4 text-sm text-rose-600 dark:text-rose-300">{deleteError}</p>}
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="outline" disabled={deleting} onClick={() => setDeleteTarget(null)}>Hủy</Button>
          <Button variant="danger" loading={deleting} onClick={() => void deleteDocument()}>
            {deleting ? 'Đang xóa…' : 'Xác nhận xóa tài liệu'}
          </Button>
        </div>
      </Modal>}
    </div>
  );
};
