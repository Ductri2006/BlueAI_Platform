import React from 'react';
import { MOCK_ADMIN_METRICS } from '../../data/mockData';
import { useAppStore } from '../../store/useAppStore';
import { 
  ShieldCheck, Cpu, DollarSign, Activity, Users, 
  Layers, Lock, AlertOctagon, ArrowUpRight, BarChart3 
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '../ui/Card';
import { Badge } from '../ui/Badge';
import { Button } from '../ui/Button';

export const AdminDashboard: React.FC = () => {
  const { showToast } = useAppStore();

  return (
    <div className="flex-1 overflow-y-auto p-6 sm:p-8 space-y-6 bg-canvas-light dark:bg-canvas-dark">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-6 h-6 text-cobalt-600 dark:text-cobalt-400" />
            <h1 className="text-xl sm:text-2xl font-bold text-slate-900 dark:text-slate-100 tracking-tight">
              Quản trị Hệ thống & Giám sát AI
            </h1>
          </div>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            Theo dõi chi phí LLM Token, định tuyến mô hình, độ trễ và phân quyền đa tổ chức (Multi-Tenant RBAC).
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <Button 
            variant="outline" 
            size="sm"
            onClick={() => showToast('Đã xuất báo cáo Audit Log dạng CSV bảo mật', 'success')}
          >
            Xuất Audit Logs
          </Button>
          <Button 
            variant="primary" 
            size="sm"
            onClick={() => showToast('Đang tối ưu lại tỷ trọng phân bổ Model Routing', 'info')}
          >
            Cấu hình Gateway
          </Button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4">
        <Card className="p-4">
          <div className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-xs">
            <span>Lượt gọi AI hôm nay</span>
            <Activity className="w-4 h-4 text-cobalt-600" />
          </div>
          <div className="text-2xl font-bold text-slate-900 dark:text-slate-100 font-mono mt-1">
            {MOCK_ADMIN_METRICS.totalRequestsToday.toLocaleString()}
          </div>
          <span className="text-[11px] text-emerald-600 font-medium">+14.2% so với hôm qua</span>
        </Card>

        <Card className="p-4">
          <div className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-xs">
            <span>Độ trễ trung bình</span>
            <Cpu className="w-4 h-4 text-indigo-600" />
          </div>
          <div className="text-2xl font-bold text-slate-900 dark:text-slate-100 font-mono mt-1">
            {MOCK_ADMIN_METRICS.avgLatencyMs} ms
          </div>
          <span className="text-[11px] text-emerald-600 font-medium">P95: 680ms (Rất nhanh)</span>
        </Card>

        <Card className="p-4">
          <div className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-xs">
            <span>Độ chính xác RAG</span>
            <ShieldCheck className="w-4 h-4 text-emerald-600" />
          </div>
          <div className="text-2xl font-bold text-emerald-600 dark:text-emerald-400 font-mono mt-1">
            {MOCK_ADMIN_METRICS.ragAccuracyScore}%
          </div>
          <span className="text-[11px] text-slate-400">Zero Hallucination Policy</span>
        </Card>

        <Card className="p-4">
          <div className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-xs">
            <span>Chi phí Token hôm nay</span>
            <DollarSign className="w-4 h-4 text-amber-600" />
          </div>
          <div className="text-2xl font-bold text-slate-900 dark:text-slate-100 font-mono mt-1">
            ${MOCK_ADMIN_METRICS.tokenCostTodayUsd}
          </div>
          <span className="text-[11px] text-slate-400">Hạn mức ngày: $50.00</span>
        </Card>

        <Card className="p-4">
          <div className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-xs">
            <span>HITL chờ phê duyệt</span>
            <AlertOctagon className="w-4 h-4 text-rose-600" />
          </div>
          <div className="text-2xl font-bold text-rose-600 dark:text-rose-400 font-mono mt-1">
            {MOCK_ADMIN_METRICS.hitlActionsPending}
          </div>
          <span className="text-[11px] text-rose-500 font-medium">Yêu cầu hoàn tiền</span>
        </Card>
      </div>

      {/* Model Gateway Distribution */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>Phân bổ Định tuyến Model & Nhà cung cấp LLM</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="space-y-3">
              {MOCK_ADMIN_METRICS.modelsRouting.map((model, idx) => (
                <div key={idx} className="p-3.5 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200/80 dark:border-slate-800 space-y-2">
                  <div className="flex items-center justify-between text-xs">
                    <div>
                      <span className="font-bold text-slate-900 dark:text-slate-100">{model.name}</span>
                      <span className="text-slate-400 ml-2">({model.provider})</span>
                    </div>
                    <span className="font-mono font-bold text-cobalt-600 dark:text-cobalt-400">{model.sharePercent}% Traffic</span>
                  </div>
                  <div className="w-full h-2 bg-slate-200 dark:bg-slate-800 rounded-full overflow-hidden">
                    <div 
                      className="h-full bg-cobalt-600 dark:bg-cobalt-500 rounded-full" 
                      style={{ width: `${model.sharePercent}%` }} 
                    />
                  </div>
                  <div className="flex justify-between text-[11px] text-slate-400 pt-0.5">
                    <span>Đơn giá trung bình: <strong className="text-slate-600 dark:text-slate-300 font-mono">{model.avgCostPer1k}</strong> / 1k tokens</span>
                    <span className="text-emerald-600 font-medium">Hoạt động ổn định</span>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>

        {/* Security & Multi-tenant RBAC Status */}
        <Card>
          <CardHeader>
            <CardTitle>Bảo mật & Multi-Tenant RBAC</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3.5 text-xs">
            <div className="p-3 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <div className="flex justify-between font-semibold text-slate-800 dark:text-slate-200">
                <span>Tenant Hiện hành:</span>
                <Badge variant="subtle" size="sm">Bluecom-Main-Org</Badge>
              </div>
              <p className="text-slate-500 dark:text-slate-400 text-[11px]">Dữ liệu được mã hóa phân vùng AES-256 theo tenant ID.</p>
            </div>

            <div className="p-3 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <div className="flex justify-between font-semibold text-slate-800 dark:text-slate-200">
                <span>Khử PII Tự động:</span>
                <span className="text-emerald-600 font-semibold">Đang bật (Active)</span>
              </div>
              <p className="text-slate-500 dark:text-slate-400 text-[11px]">Tự động masking SĐT, Email và số thẻ thanh toán trước khi gửi sang LLM.</p>
            </div>

            <div className="p-3 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <div className="flex justify-between font-semibold text-slate-800 dark:text-slate-200">
                <span>Audit Trail:</span>
                <span className="text-cobalt-600 font-semibold font-mono">100% Logs</span>
              </div>
              <p className="text-slate-500 dark:text-slate-400 text-[11px]">Lưu vết toàn bộ truy vấn và hành động HITL theo chuẩn SOC 2.</p>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
};
