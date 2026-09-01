import React, { useState } from 'react';
import { MOCK_DOCUMENTS } from '../../data/mockData';
import { useAppStore } from '../../store/useAppStore';
import { 
  BookOpen, Plus, Search, Filter, RefreshCw, 
  FileText, CheckCircle2, AlertTriangle, Clock, 
  ArrowUpRight, Database, Layers, UploadCloud 
} from 'lucide-react';
import { Button } from '../ui/Button';
import { Badge } from '../ui/Badge';
import { Card, CardHeader, CardTitle, CardContent } from '../ui/Card';

export const KnowledgeDashboard: React.FC = () => {
  const { showToast, openEvidence } = useAppStore();
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('all');

  const filteredDocs = MOCK_DOCUMENTS.filter(doc => {
    const matchesSearch = doc.title.toLowerCase().includes(searchTerm.toLowerCase()) || doc.id.toLowerCase().includes(searchTerm.toLowerCase());
    const matchesCategory = selectedCategory === 'all' || doc.category === selectedCategory;
    return matchesSearch && matchesCategory;
  });

  return (
    <div className="flex-1 overflow-y-auto p-6 sm:p-8 space-y-6 bg-canvas-light dark:bg-canvas-dark">
      {/* Top Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <BookOpen className="w-6 h-6 text-cobalt-600 dark:text-cobalt-400" />
            <h1 className="text-xl sm:text-2xl font-bold text-slate-900 dark:text-slate-100 tracking-tight">
              Quản lý Tài liệu & Tri thức RAG
            </h1>
          </div>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            Kiểm soát nguồn dữ liệu chính sách, phân đoạn văn bản (chunks) và mức độ tin cậy của câu trả lời AI.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <Button 
            variant="outline" 
            size="sm"
            onClick={() => showToast('Đang quét và đồng bộ lại 132 vector chunks từ Hub...', 'info')}
          >
            <RefreshCw className="w-4 h-4 mr-1.5" />
            Đồng bộ Vectors
          </Button>
          <Button 
            variant="primary" 
            size="sm"
            onClick={() => showToast('Mở cửa sổ tải lên tài liệu PDF/Docx chính sách mới', 'info')}
          >
            <UploadCloud className="w-4 h-4 mr-1.5" />
            Thêm tài liệu mới
          </Button>
        </div>
      </div>

      {/* KPI Stats Strip */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <Card className="p-4">
          <span className="text-xs text-slate-500 dark:text-slate-400">Tổng tài liệu đang hiệu lực</span>
          <div className="flex items-baseline justify-between mt-1">
            <span className="text-2xl font-bold text-slate-900 dark:text-slate-100">42</span>
            <span className="text-xs font-semibold text-emerald-600">+3 tháng này</span>
          </div>
        </Card>

        <Card className="p-4">
          <span className="text-xs text-slate-500 dark:text-slate-400">Tổng số Chunk Vectors</span>
          <div className="flex items-baseline justify-between mt-1">
            <span className="text-2xl font-bold text-slate-900 dark:text-slate-100 font-mono">1,842</span>
            <span className="text-xs text-slate-400">Embeddings: 768d</span>
          </div>
        </Card>

        <Card className="p-4">
          <span className="text-xs text-slate-500 dark:text-slate-400">Lượt trích xuất (Retrieval Hits)</span>
          <div className="flex items-baseline justify-between mt-1">
            <span className="text-2xl font-bold text-cobalt-600 dark:text-cobalt-400 font-mono">8,240</span>
            <span className="text-xs text-emerald-600">Độ khớp 98.4%</span>
          </div>
        </Card>

        <Card className="p-4 border-amber-200 dark:border-amber-900/60 bg-amber-50/20">
          <span className="text-xs text-amber-700 dark:text-amber-400 font-semibold">Cần cập nhật / Sắp hết hạn</span>
          <div className="flex items-baseline justify-between mt-1">
            <span className="text-2xl font-bold text-amber-800 dark:text-amber-300">2</span>
            <span className="text-xs text-amber-600">Trong 30 ngày</span>
          </div>
        </Card>
      </div>

      {/* Filter & Search Bar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-3 p-3 rounded-xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800">
        <div className="relative w-full sm:w-80">
          <Search className="w-4 h-4 absolute left-3 top-2.5 text-slate-400" />
          <input
            type="text"
            placeholder="Tìm theo tên chính sách, mã văn bản..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pl-9 pr-3 py-1.5 text-xs bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg focus:outline-none focus:border-cobalt-500 text-slate-800 dark:text-slate-100"
          />
        </div>

        <div className="flex items-center gap-2 w-full sm:w-auto">
          <span className="text-xs text-slate-400 shrink-0">Danh mục:</span>
          <select
            value={selectedCategory}
            onChange={(e) => setSelectedCategory(e.target.value)}
            className="text-xs px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 focus:outline-none"
          >
            <option value="all">Tất cả danh mục</option>
            <option value="Chính sách CSKH & Đơn hàng">Chính sách CSKH & Đơn hàng</option>
            <option value="Tài liệu Kỹ thuật & Telco">Tài liệu Kỹ thuật & Telco</option>
            <option value="Chính sách Kênh Phân Phối">Chính sách Kênh Phân Phối</option>
          </select>
        </div>
      </div>

      {/* Documents Table */}
      <Card className="overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="bg-slate-50 dark:bg-slate-900/80 text-slate-600 dark:text-slate-400 border-b border-slate-200 dark:border-slate-800">
              <tr>
                <th className="p-3.5 pl-5">Mã & Tên tài liệu</th>
                <th className="p-3.5">Danh mục</th>
                <th className="p-3.5">Phiên bản</th>
                <th className="p-3.5">Chủ quản</th>
                <th className="p-3.5">Số Chunks</th>
                <th className="p-3.5">Trích xuất</th>
                <th className="p-3.5">Trạng thái</th>
                <th className="p-3.5 pr-5 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800/80">
              {filteredDocs.map((doc) => (
                <tr key={doc.id} className="hover:bg-slate-50/80 dark:hover:bg-slate-800/40 transition-colors">
                  <td className="p-3.5 pl-5">
                    <div className="font-semibold text-slate-900 dark:text-slate-100 text-sm">{doc.title}</div>
                    <div className="font-mono text-[11px] text-slate-400 mt-0.5">{doc.id}</div>
                  </td>
                  <td className="p-3.5 text-slate-600 dark:text-slate-400 font-medium">{doc.category}</td>
                  <td className="p-3.5 font-mono text-slate-700 dark:text-slate-300">{doc.version}</td>
                  <td className="p-3.5 text-slate-600 dark:text-slate-400">{doc.owner}</td>
                  <td className="p-3.5 font-mono font-semibold text-slate-800 dark:text-slate-200">{doc.chunksCount} chunks</td>
                  <td className="p-3.5 font-mono text-cobalt-600 dark:text-cobalt-400 font-semibold">{doc.retrievalHitCount}</td>
                  <td className="p-3.5">
                    <Badge variant={doc.status === 'active' ? 'confirmed' : 'inferred'} size="sm">
                      {doc.status === 'active' ? 'Đang hiệu lực' : 'Sắp hết hạn'}
                    </Badge>
                  </td>
                  <td className="p-3.5 pr-5 text-right">
                    <Button 
                      variant="outline" 
                      size="sm" 
                      className="text-xs h-7 px-2"
                      onClick={() => showToast(`Đang mở thanh kiểm tra đoạn trích chunk cho: ${doc.id}`, 'info')}
                    >
                      Kiểm tra Chunks
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
};
