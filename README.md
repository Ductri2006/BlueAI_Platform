# BlueAI Platform — Frontend

Giao diện React/TypeScript cho BlueAI Enterprise Platform, bám theo Master Prototype v2 và bộ design token trong `figma-export/`.

## Chạy dự án

```bash
npm install
npm run dev
```

## Kiểm tra production build

```bash
npm run build
npm run preview
```

Frontend sử dụng Vite, React 19, Tailwind CSS, Lucide và Three.js. Các module 3D được tách chunk để giảm thời gian tải giao diện ban đầu.
