import React, { useEffect, useRef } from 'react';
import * as THREE from 'three';
import { AIStreamStatus } from '../../types';

interface IntelligenceOrbProps {
  status?: AIStreamStatus;
  size?: number;
  interactive?: boolean;
}

export const IntelligenceOrb: React.FC<IntelligenceOrbProps> = ({
  status = 'idle',
  size = 140,
  interactive = true
}) => {
  const mountRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = mountRef.current;
    if (!container) return;

    // Strict DOM reset to prevent duplicate canvas
    container.innerHTML = '';

    // Scene, Camera, WebGL Renderer
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 1000);
    camera.position.z = 3.6;

    const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true });
    renderer.setSize(size, size);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.domElement.style.display = 'block';
    renderer.domElement.style.width = `${size}px`;
    renderer.domElement.style.height = `${size}px`;
    container.appendChild(renderer.domElement);

    // Root Group
    const group = new THREE.Group();
    scene.add(group);

    // 1. Inner Crystal Icosahedron
    const crystalGeom = new THREE.IcosahedronGeometry(0.72, 1);
    const crystalMat = new THREE.MeshBasicMaterial({
      color: 0x1E4FD8,
      wireframe: true,
      transparent: true,
      opacity: 0.55,
    });
    const crystalMesh = new THREE.Mesh(crystalGeom, crystalMat);
    group.add(crystalMesh);

    // 2. High-Density Floating Quantum Star Field
    const particleCount = 180;
    const particlesGeom = new THREE.BufferGeometry();
    const positions = new Float32Array(particleCount * 3);
    const colors = new Float32Array(particleCount * 3);

    const cyanColor = new THREE.Color(0x38BDF8); // Electric Cyan
    const blueColor = new THREE.Color(0x2563EB); // Vivid Cobalt Blue

    for (let i = 0; i < particleCount; i++) {
      const radius = 0.95 + Math.random() * 0.55;
      const theta = Math.random() * Math.PI * 2;
      const phi = Math.acos((Math.random() * 2) - 1);

      positions[i * 3] = radius * Math.sin(phi) * Math.cos(theta);
      positions[i * 3 + 1] = radius * Math.sin(phi) * Math.sin(theta);
      positions[i * 3 + 2] = radius * Math.cos(phi);

      const mixed = cyanColor.clone().lerp(blueColor, Math.random());
      colors[i * 3] = mixed.r;
      colors[i * 3 + 1] = mixed.g;
      colors[i * 3 + 2] = mixed.b;
    }

    particlesGeom.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    particlesGeom.setAttribute('color', new THREE.BufferAttribute(colors, 3));

    const particlesMat = new THREE.PointsMaterial({
      size: 0.055,
      vertexColors: true,
      transparent: true,
      opacity: 0.95,
    });

    const particles = new THREE.Points(particlesGeom, particlesMat);
    group.add(particles);

    // 3. Gyroscopic Orbit Rings
    const ring1Geom = new THREE.TorusGeometry(1.22, 0.015, 16, 64);
    const ring1Mat = new THREE.MeshBasicMaterial({ color: 0x38BDF8, transparent: true, opacity: 0.45 });
    const ring1 = new THREE.Mesh(ring1Geom, ring1Mat);
    ring1.rotation.x = Math.PI / 3;
    group.add(ring1);

    const ring2Geom = new THREE.TorusGeometry(1.36, 0.012, 16, 64);
    const ring2Mat = new THREE.MeshBasicMaterial({ color: 0x1E4FD8, transparent: true, opacity: 0.35 });
    const ring2 = new THREE.Mesh(ring2Geom, ring2Mat);
    ring2.rotation.y = Math.PI / 4;
    ring2.rotation.x = -Math.PI / 4;
    group.add(ring2);

    // 4. Glowing Energy Core Sphere
    const coreGeom = new THREE.SphereGeometry(0.38, 20, 20);
    const coreMat = new THREE.MeshBasicMaterial({
      color: 0x0EA5E9,
      transparent: true,
      opacity: 0.32,
    });
    const coreMesh = new THREE.Mesh(coreGeom, coreMat);
    group.add(coreMesh);

    // Parallax
    let targetRotationX = 0;
    let targetRotationY = 0;

    const handleMouseMove = (event: MouseEvent) => {
      if (!interactive || !container) return;
      const rect = container.getBoundingClientRect();
      const mouseX = ((event.clientX - rect.left) / rect.width - 0.5) * 2;
      const mouseY = ((event.clientY - rect.top) / rect.height - 0.5) * 2;
      targetRotationY = mouseX * 0.9;
      targetRotationX = mouseY * 0.9;
    };

    window.addEventListener('mousemove', handleMouseMove);

    // Animation Loop
    let animationFrameId: number;
    let clock = new THREE.Clock();

    const animate = () => {
      animationFrameId = requestAnimationFrame(animate);
      const elapsedTime = clock.getElapsedTime();

      // Dynamic rotation speed based on status
      let speed = 0.7;
      if (status === 'searching' || status === 'tool_calling') speed = 2.6;
      if (status === 'streaming') speed = 1.8;

      group.rotation.y += 0.008 * speed;
      group.rotation.x += 0.004 * speed;

      ring1.rotation.z += 0.012 * speed;
      ring2.rotation.z -= 0.008 * speed;

      // Parallax lerp
      group.rotation.y += (targetRotationY - group.rotation.y) * 0.06;
      group.rotation.x += (targetRotationX - group.rotation.x) * 0.06;

      // Pulse core scale
      const pulse = 1 + Math.sin(elapsedTime * (status === 'searching' ? 6 : 2.5)) * 0.1;
      coreMesh.scale.set(pulse, pulse, pulse);

      // Status color changes
      if (status === 'searching' || status === 'tool_calling') {
        crystalMat.color.setHex(0x38BDF8);
        coreMat.color.setHex(0x2563EB);
        ring1Mat.color.setHex(0x38BDF8);
      } else if (status === 'abstained') {
        crystalMat.color.setHex(0xF59E0B);
        coreMat.color.setHex(0xFBBF24);
        ring1Mat.color.setHex(0xF59E0B);
      } else {
        crystalMat.color.setHex(0x1E4FD8);
        coreMat.color.setHex(0x0EA5E9);
        ring1Mat.color.setHex(0x38BDF8);
      }

      renderer.render(scene, camera);
    };

    animate();

    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      cancelAnimationFrame(animationFrameId);
      if (container && renderer.domElement.parentNode === container) {
        container.removeChild(renderer.domElement);
      }
      renderer.dispose();
    };
  }, [size, status, interactive]);

  return (
    <div 
      className="relative flex items-center justify-center pointer-events-none select-none shrink-0"
      style={{ width: size, height: size }}
    >
      {/* Multi-layer ambient glowing pedestal */}
      <div 
        className="absolute inset-0 rounded-full bg-gradient-to-tr from-cobalt-600/25 via-cyan-400/20 to-transparent blur-xl transform scale-95 -z-10" 
      />
      <div 
        className="absolute inset-4 rounded-full bg-cyan-400/10 blur-md -z-10 animate-pulse" 
      />
      <div 
        ref={mountRef} 
        className="w-full h-full flex items-center justify-center overflow-hidden" 
        style={{ width: size, height: size }} 
      />
    </div>
  );
};
