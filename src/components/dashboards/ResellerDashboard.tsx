import React from 'react';
import { useAppStore } from '../../store/useAppStore';
import { Store, TrendingUp, DollarSign, ShoppingCart, ShieldCheck, Tag, Search, ArrowUpRight } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '../ui/Card';
import { Button } from '../ui/Button';
import { Badge } from '../ui/Badge';

export const ResellerDashboard: React.FC = () => {
  const { showToast, sendMessage, setActiveRole } = useAppStore();

  const resellerProducts = [
    { code: 'JP-5G-07D', name: 'eSIM Nhật Bản 7 Ngày 5G (SoftBank)', retail: '220.000đ', wholesale: '167.200đ', margin: '24%', stock: 'Sẵn sàng' },
    { code: 'KR-5G-05D', name: 'eSIM Hàn Quốc 5 Ngày Data Cao (SKT)', retail: '180.000đ', wholesale: '136.800đ', margin: '24%', stock: 'Sẵn sàng' },
    { code: 'TH-5G-10D', name: 'eSIM Thái Lan 10 Ngày Không Giới Hạn (TrueMove)', retail: '150.000đ', wholesale: '114.000đ', margin: '24%', stock: 'Sẵn sàng' },
    { code: 'EU-33C-15D', name: 'eSIM Châu Âu 33 Nước 10GB Data 15 Ngày', retail: '420.000đ', wholesale: '319.200đ', margin: '24%', stock: 'Sẵn sàng' },
  ];

  return (
    <div className="flex-1 overflow-y-auto p-6 sm:p-8 space-y-6 bg-canvas-light dark:bg-canvas-dark">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <Store className="w-6 h-6 text-cobalt-600 dark:text-cobalt-400" />
            <h1 className="text-xl sm:text-2xl font-bold text-slate-900 dark:text-slate-100 tracking-tight">
              Cổng Đại lý Phân phối
            </h1>
          </div>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            Đại lý: <strong>GoTravel International (Cấp 1 - Kim Cương)</strong> • Chiết khấu cố định <strong>24%</strong>
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <Button 
            variant="outline" 
            size="sm"
            onClick={() => showToast('Đang tải sao kê công nợ tháng 02/2026', 'info')}
          >
            Sao kê công nợ
          </Button>
          <Button 
            variant="primary" 
            size="sm"
            onClick={() => showToast('Mở cửa sổ xuất mã QR số lượng lớn (Bulk Issuance)', 'info')}
          >
            <ShoppingCart className="w-4 h-4 mr-1.5" />
            Xuất kho mã eSIM loạt
          </Button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <Card className="p-4">
          <span className="text-xs text-slate-500 dark:text-slate-400">Doanh số tháng 02/2026</span>
          <div className="text-2xl font-bold text-slate-900 dark:text-slate-100 font-mono mt-1">
            68.450.000đ
          </div>
          <span className="text-xs font-semibold text-emerald-600">Đạt chỉ tiêu Quý I (114%)</span>
        </Card>

        <Card className="p-4">
          <span className="text-xs text-slate-500 dark:text-slate-400">Hoa hồng tích lũy</span>
          <div className="text-2xl font-bold text-cobalt-600 dark:text-cobalt-400 font-mono mt-1">
            16.428.000đ
          </div>
          <span className="text-xs text-slate-400">Thanh toán ngày 05 hàng tháng</span>
        </Card>

        <Card className="p-4">
          <span className="text-xs text-slate-500 dark:text-slate-400">Hạn mức Tín dụng (Credit Line)</span>
          <div className="text-2xl font-bold text-slate-900 dark:text-slate-100 font-mono mt-1">
            50.000.000đ
          </div>
          <span className="text-xs text-slate-400">Đã dùng: 14.200.000đ (Còn 71%)</span>
        </Card>

        <Card className="p-4">
          <span className="text-xs text-slate-500 dark:text-slate-400">Hạng Đại lý Hiện tại</span>
          <div className="text-2xl font-bold text-amber-600 dark:text-amber-400 mt-1">
            Kim Cương ★
          </div>
          <span className="text-xs text-emerald-600 font-medium">Chiết khấu tối đa + Thưởng quý</span>
        </Card>
      </div>

      {/* Wholesale Catalog Price Table */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between">
            <span>Bảng giá Bán buôn & Chiết khấu eSIM Quốc tế</span>
            <Badge variant="confirmed" size="sm">Giá áp dụng riêng cho Đại lý Cấp 1</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="bg-slate-50 dark:bg-slate-900/80 text-slate-600 dark:text-slate-400 border-b border-slate-200 dark:border-slate-800">
                <tr>
                  <th className="p-3">Mã gói</th>
                  <th className="p-3">Tên sản phẩm</th>
                  <th className="p-3">Giá bán lẻ đề xuất</th>
                  <th className="p-3 text-cobalt-600">Giá Đại lý (Wholesale)</th>
                  <th className="p-3">Mức Margin</th>
                  <th className="p-3">Kho mã</th>
                  <th className="p-3 text-right">Hành động</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                {resellerProducts.map((p) => (
                  <tr key={p.code} className="hover:bg-slate-50/80 dark:hover:bg-slate-800/40">
                    <td className="p-3 font-mono font-semibold text-slate-800 dark:text-slate-200">{p.code}</td>
                    <td className="p-3 font-medium text-slate-900 dark:text-slate-100">{p.name}</td>
                    <td className="p-3 text-slate-400 line-through">{p.retail}</td>
                    <td className="p-3 font-mono font-bold text-cobalt-600 dark:text-cobalt-400 text-sm">{p.wholesale}</td>
                    <td className="p-3 font-semibold text-emerald-600">+{p.margin}</td>
                    <td className="p-3">
                      <span className="inline-flex items-center gap-1 text-emerald-600 font-medium">
                        <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                        {p.stock}
                      </span>
                    </td>
                    <td className="p-3 text-right">
                      <Button 
                        size="sm" 
                        variant="primary" 
                        className="h-7 px-3 text-xs"
                        onClick={() => showToast(`Đã thêm gói ${p.code} vào danh sách xuất mã`, 'success')}
                      >
                        Xuất mã ngay
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  );
};
