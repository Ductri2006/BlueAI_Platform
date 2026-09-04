import React, { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { Network, X } from 'lucide-react';
import { blueAiApi, type DocumentResponse } from '../../api/blueAiApi';

interface KnowledgeGraph3DModalProps { isOpen: boolean; onClose: () => void; }
interface NodeData { id: string; name: string; chunks: number; status: string; position: THREE.Vector3; }

export const KnowledgeGraph3DModal: React.FC<KnowledgeGraph3DModalProps> = ({ isOpen, onClose }) => {
  const mount = useRef<HTMLDivElement>(null);
  const [documents, setDocuments] = useState<DocumentResponse[]>([]);
  const [selected, setSelected] = useState<NodeData>();

  useEffect(() => {
    if (isOpen) void blueAiApi.listDocuments().then(setDocuments).catch(() => setDocuments([]));
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen || !mount.current) return;
    const width = mount.current.clientWidth || 800;
    const height = mount.current.clientHeight || 500;
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(50, width / height, 0.1, 100);
    camera.position.z = 9;
    const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true });
    renderer.setSize(width, height);
    mount.current.appendChild(renderer.domElement);
    const group = new THREE.Group();
    scene.add(group);

    const nodes: NodeData[] = [{ id: 'rag-core', name: 'BlueAI RAG', chunks: documents.reduce((sum, d) => sum + (d.producedChunkCount || 0), 0), status: 'CORE', position: new THREE.Vector3() }];
    documents.forEach((document, index) => {
      const angle = (index / Math.max(documents.length, 1)) * Math.PI * 2;
      nodes.push({ id: document.id, name: document.originalFilename, chunks: document.producedChunkCount || 0, status: document.status, position: new THREE.Vector3(Math.cos(angle) * 3, Math.sin(angle) * 2.3, index % 2 ? 0.5 : -0.5) });
    });

    const meshes: THREE.Mesh[] = [];
    nodes.forEach((node, index) => {
      const mesh = new THREE.Mesh(new THREE.SphereGeometry(index === 0 ? 0.55 : 0.32, 20, 20), new THREE.MeshBasicMaterial({ color: index === 0 ? 0x2563eb : node.status === 'READY' ? 0x10b981 : node.status === 'FAILED' ? 0xef4444 : 0xf59e0b }));
      mesh.position.copy(node.position);
      mesh.userData = node;
      group.add(mesh);
      meshes.push(mesh);
      if (index > 0) group.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints([nodes[0].position, node.position]), new THREE.LineBasicMaterial({ color: 0x38bdf8, transparent: true, opacity: 0.35 })));
    });

    const raycaster = new THREE.Raycaster();
    const mouse = new THREE.Vector2();
    const click = (event: MouseEvent) => {
      const rect = renderer.domElement.getBoundingClientRect();
      mouse.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1);
      raycaster.setFromCamera(mouse, camera);
      const hit = raycaster.intersectObjects(meshes)[0];
      if (hit) setSelected(hit.object.userData as NodeData);
    };
    renderer.domElement.addEventListener('click', click);
    let animation = 0;
    const draw = () => { animation = requestAnimationFrame(draw); group.rotation.y += 0.002; renderer.render(scene, camera); };
    draw();
    return () => {
      cancelAnimationFrame(animation);
      renderer.domElement.removeEventListener('click', click);
      renderer.dispose();
      if (mount.current?.contains(renderer.domElement)) mount.current.removeChild(renderer.domElement);
    };
  }, [isOpen, documents]);

  if (!isOpen) return null;
  return (
    <div className="fixed inset-0 z-50 p-4 flex items-center justify-center bg-slate-950/80" role="dialog" aria-modal="true">
      <div className="w-full max-w-4xl h-[600px] rounded-2xl bg-slate-950 border border-slate-700 overflow-hidden flex flex-col">
        <div className="p-4 flex justify-between text-white border-b border-slate-800"><h3 className="font-bold flex gap-2"><Network className="w-5 h-5" />Knowledge Graph từ Document API</h3><button onClick={onClose}><X className="w-5 h-5" /></button></div>
        <div className="relative flex-1"><div ref={mount} className="w-full h-full" />{selected && <div className="absolute left-4 bottom-4 rounded-xl bg-slate-900/95 border border-slate-700 p-4 text-white"><p className="font-bold">{selected.name}</p><p className="text-xs text-slate-400 mt-1">{selected.chunks} chunks · {selected.status}</p></div>}{documents.length === 0 && <p className="absolute inset-x-0 top-6 text-center text-sm text-slate-400">Kho tài liệu hiện đang rỗng.</p>}</div>
      </div>
    </div>
  );
};
