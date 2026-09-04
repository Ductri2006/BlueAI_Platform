import React, { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { useAppStore } from '../../store/useAppStore';
import { INITIAL_CITATIONS } from '../../data/mockData';
import { X, Network, ExternalLink, ShieldCheck, Sparkles, ZoomIn, ZoomOut, RotateCcw } from 'lucide-react';
import { Button } from '../ui/Button';

interface KnowledgeGraph3DModalProps {
  isOpen: boolean;
  onClose: () => void;
}

interface NodeData {
  id: string;
  name: string;
  category: string;
  chunks: number;
  color: number;
  citationKey?: string;
  pos: THREE.Vector3;
}

export const KnowledgeGraph3DModal: React.FC<KnowledgeGraph3DModalProps> = ({ isOpen, onClose }) => {
  const { openEvidence, showToast } = useAppStore();
  const mountRef = useRef<HTMLDivElement>(null);
  const [selectedNode, setSelectedNode] = useState<NodeData | null>(null);

  useEffect(() => {
    if (!isOpen || !mountRef.current) return;

    const width = mountRef.current.clientWidth || 800;
    const height = mountRef.current.clientHeight || 500;

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(50, width / height, 0.1, 1000);
    camera.position.set(0, 0, 9);

    const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true });
    renderer.setSize(width, height);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    mountRef.current.appendChild(renderer.domElement);

    const rootGroup = new THREE.Group();
    scene.add(rootGroup);

    // Knowledge Nodes dataset
    const nodes: NodeData[] = [
      { id: 'hub-core', name: 'Bluecom HUB Core (RAG Engine)', category: 'Core Gateway', chunks: 1842, color: 0x1E4FD8, pos: new THREE.Vector3(0, 0, 0) },
      { id: 'pol-042', name: 'Chính sách Hoàn hủy eSIM', category: 'Chính sách', chunks: 24, color: 0x10B981, citationKey: 'cit-1', pos: new THREE.Vector3(-2.8, 1.4, 0.5) },
      { id: 'tech-108', name: 'Quy chuẩn Mạng SoftBank/Docomo', category: 'Kỹ thuật Telco', chunks: 38, color: 0x0EA5E9, citationKey: 'cit-2', pos: new THREE.Vector3(2.6, 1.6, -0.4) },
      { id: 'b2b-019', name: 'Chiết khấu Đại lý Cấp 1', category: 'B2B Phân phối', chunks: 16, color: 0x6366F1, citationKey: 'cit-3', pos: new THREE.Vector3(-2.2, -1.8, 0.8) },
      { id: 'prod-201', name: 'Bảng cước 33 Nước Châu Âu', category: 'Sản phẩm', chunks: 52, color: 0xF59E0B, pos: new THREE.Vector3(2.4, -1.5, -0.6) },
      { id: 'oms-esim', name: 'Telco ICCID Management', category: 'Hệ thống', chunks: 12, color: 0x3B82F6, pos: new THREE.Vector3(0, 2.5, 0.2) },
      { id: 'hitl-auth', name: 'HITL Financial Audit Trail', category: 'Kiểm toán', chunks: 8, color: 0xEF4444, pos: new THREE.Vector3(0, -2.4, -0.5) },
    ];

    const nodeMeshes: THREE.Mesh[] = [];

    // Create 3D Node Spheres
    nodes.forEach((n) => {
      const size = n.id === 'hub-core' ? 0.6 : 0.35;
      const geom = new THREE.SphereGeometry(size, 24, 24);
      const mat = new THREE.MeshBasicMaterial({
        color: n.color,
        wireframe: n.id === 'hub-core',
      });
      const mesh = new THREE.Mesh(geom, mat);
      mesh.position.copy(n.pos);
      mesh.userData = n;
      rootGroup.add(mesh);
      nodeMeshes.push(mesh);

      // Add a subtle glowing ring
      const ringGeom = new THREE.RingGeometry(size * 1.2, size * 1.35, 32);
      const ringMat = new THREE.MeshBasicMaterial({ color: n.color, side: THREE.DoubleSide, transparent: true, opacity: 0.4 });
      const ringMesh = new THREE.Mesh(ringGeom, ringMat);
      ringMesh.position.copy(n.pos);
      ringMesh.rotation.x = Math.PI / 2;
      rootGroup.add(ringMesh);
    });

    // Create Connecting Edges / Laser Lines
    nodes.slice(1).forEach((n) => {
      const points = [nodes[0].pos, n.pos];
      const lineGeom = new THREE.BufferGeometry().setFromPoints(points);
      const lineMat = new THREE.LineBasicMaterial({
        color: 0x38BDF8,
        transparent: true,
        opacity: 0.35,
      });
      const line = new THREE.Line(lineGeom, lineMat);
      rootGroup.add(line);
    });

    // Raycaster for mouse click interaction
    const raycaster = new THREE.Raycaster();
    const mouse = new THREE.Vector2();

    const handleCanvasClick = (event: MouseEvent) => {
      const rect = renderer.domElement.getBoundingClientRect();
      mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
      mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;

      raycaster.setFromCamera(mouse, camera);
      const intersects = raycaster.intersectObjects(nodeMeshes);

      if (intersects.length > 0) {
        const clickedData = intersects[0].object.userData as NodeData;
        setSelectedNode(clickedData);
        showToast(`Đã chọn nút tri thức: ${clickedData.name}`, 'info');
      }
    };

    renderer.domElement.addEventListener('click', handleCanvasClick);

    // Mouse drag rotation
    let isDragging = false;
    let previousMousePosition = { x: 0, y: 0 };

    const handleMouseDown = (e: MouseEvent) => {
      isDragging = true;
      previousMousePosition = { x: e.clientX, y: e.clientY };
    };

    const handleMouseMove = (e: MouseEvent) => {
      if (!isDragging) return;
      const deltaX = e.clientX - previousMousePosition.x;
      const deltaY = e.clientY - previousMousePosition.y;

      rootGroup.rotation.y += deltaX * 0.006;
      rootGroup.rotation.x += deltaY * 0.006;

      previousMousePosition = { x: e.clientX, y: e.clientY };
    };

    const handleMouseUp = () => {
      isDragging = false;
    };

    renderer.domElement.addEventListener('mousedown', handleMouseDown);
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);

    // Animation Loop
    let animId: number;
    const animate = () => {
      animId = requestAnimationFrame(animate);
      if (!isDragging) {
        rootGroup.rotation.y += 0.002;
      }
      renderer.render(scene, camera);
    };
    animate();

    return () => {
      cancelAnimationFrame(animId);
      renderer.domElement.removeEventListener('click', handleCanvasClick);
      renderer.domElement.removeEventListener('mousedown', handleMouseDown);
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
      if (mountRef.current?.contains(renderer.domElement)) {
        mountRef.current.removeChild(renderer.domElement);
      }
      renderer.dispose();
    };
  }, [isOpen]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-md animate-in fade-in" role="dialog" aria-modal="true" aria-label="Mạng lưới tri thức 3D">
      <div className="w-full max-w-4xl h-[min(620px,calc(100dvh-2rem))] rounded-modal bg-white dark:bg-[#0B1328] border border-slate-200 dark:border-slate-800 shadow-2xl flex flex-col overflow-hidden animate-in zoom-in-95">
        {/* Modal Header */}
        <div className="px-5 py-4 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between bg-slate-50/50 dark:bg-slate-900/50">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-cobalt-50 dark:bg-cobalt-950 text-cobalt-600 dark:text-cobalt-400 flex items-center justify-center">
              <Network className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100">
                Mạng Lưới Tri Thức 3D (Interactive RAG Knowledge Graph)
              </h3>
              <p className="text-[11px] text-slate-400">
                Kéo chuột để xoay 3D • Nhấp vào nút để mở căn cứ trích xuất
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* 3D Canvas Area */}
        <div className="flex-1 relative bg-slate-950 flex items-center justify-center cursor-grab active:cursor-grabbing">
          <div ref={mountRef} className="w-full h-full" />

          {/* Floating Selected Node Info Card */}
          {selectedNode && (
            <div className="absolute bottom-4 left-4 right-4 sm:right-auto sm:w-80 p-4 rounded-xl bg-slate-900/90 backdrop-blur-md border border-slate-700 text-white shadow-2xl animate-in slide-in-from-bottom-2">
              <div className="flex items-center justify-between mb-1">
                <span className="text-[10.5px] font-bold uppercase tracking-wider text-cobalt-400">
                  {selectedNode.category}
                </span>
                <span className="text-[11px] font-mono text-emerald-400 font-semibold">
                  {selectedNode.chunks} Chunks
                </span>
              </div>
              <h4 className="text-sm font-bold leading-snug">{selectedNode.name}</h4>
              <p className="text-[11.5px] text-slate-300 mt-1">
                Được liên kết với HUB Core và kiểm duyệt bảo mật tự động.
              </p>

              {selectedNode.citationKey && INITIAL_CITATIONS[selectedNode.citationKey] && (
                <Button
                  size="sm"
                  variant="primary"
                  className="w-full mt-3 text-xs"
                  onClick={() => {
                    openEvidence(INITIAL_CITATIONS[selectedNode.citationKey!]);
                    onClose();
                  }}
                >
                  <ExternalLink className="w-3.5 h-3.5 mr-1" />
                  Mở chi tiết căn cứ & đoạn trích
                </Button>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
