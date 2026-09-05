import { useEffect, useRef } from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { robotActivity, type EquipmentState, type MapNode, type RobotState } from '../types/telemetry';

interface Warehouse3DProps {
  robots: RobotState[];
  equipment: EquipmentState[];
  nodes: MapNode[];
  width?: number;
  height?: number;
}

const ROBOT_RADIUS = 0.5;
const ROBOT_HEIGHT = 0.7;
const EQUIPMENT_W = 6;
const EQUIPMENT_H = 1.2;
const EQUIPMENT_D = 1.6;
const NODE_RADIUS = 0.4;

const COLORS = {
  floor: 0x121820,
  grid: 0x2a3441,
  driving: 0x35c8e8,
  returning: 0x4a94a8,
  idle: 0x8a9099,
  charging: 0xe0a03c,
  paused: 0xe05a44,
  offline: 0x555b63,
  running: 0x4bd18a,
  stopped: 0xe0a03c,
  alarm: 0xe05a44,
  pick: 0x5c7594,
  drop: 0x4bd18a,
  waypoint: 0x3a444f,
  route: 0x35c8e8,
} as const;

/**
 * 현장 좌표(x, y)를 three 의 XZ 평면으로 옮긴다.
 *
 * 현장은 y 가 위로 증가하고 three 의 바닥은 XZ 평면이다.
 * z = height - y 로 두면 2D 맵과 같은 배치가 된다 — 소터가 안쪽, 대기 열이 앞쪽.
 */
function toWorld(x: number, y: number, mapHeight: number): [number, number] {
  return [x, mapHeight - y];
}

/** [x, z] 를 three 의 [x, y, z] 로. 선은 바닥에서 살짝 띄운다. */
function insertY([x, z]: [number, number]): [number, number, number] {
  return [x, 0.5, z];
}

/** 캔버스에 글자를 그려 스프라이트 라벨로 쓴다. 객체가 20여 개라 이 정도면 충분하다. */
function makeLabel(text: string, color: string): THREE.Sprite {
  const canvas = document.createElement('canvas');
  canvas.width = 256;
  canvas.height = 64;
  const ctx = canvas.getContext('2d');
  if (ctx) {
    ctx.font = 'bold 36px monospace';
    ctx.fillStyle = color;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(text, 128, 32);
  }
  const texture = new THREE.CanvasTexture(canvas);
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, transparent: true }));
  sprite.scale.set(4, 1, 1);
  return sprite;
}

function robotColor(robot: RobotState): number {
  return COLORS[robotActivity(robot)];
}

function equipmentColor(status: EquipmentState['status']): number {
  if (status === 'ALARM') return COLORS.alarm;
  if (status === 'STOPPED') return COLORS.stopped;
  return COLORS.running;
}

/**
 * 2D 맵과 같은 데이터를 3D 로 그린다.
 *
 * three 객체는 React 렌더링 밖에서 관리한다. 매 텔레메트리마다 씬을 다시 만들면
 * 초당 수십 번 GPU 리소스를 할당하게 되므로, 메시는 재사용하고 위치·색만 옮긴다.
 */
export function Warehouse3D({ robots, equipment, nodes, width = 40, height = 25 }: Warehouse3DProps) {
  const mountRef = useRef<HTMLDivElement>(null);
  const sceneRef = useRef<THREE.Scene | null>(null);
  const robotMeshes = useRef(new Map<string, THREE.Group>());
  const equipmentMeshes = useRef(new Map<string, THREE.Mesh>());
  const nodeGroupRef = useRef<THREE.Group | null>(null);
  const routeGroupRef = useRef<THREE.Group | null>(null);

  // 씬은 한 번만 만든다.
  useEffect(() => {
    const mount = mountRef.current;
    if (!mount) return;

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x0d1117);
    sceneRef.current = scene;

    // 위에서 비스듬히 내려다본다. 너무 낮으면 로봇이 서로 가리고, 너무 높으면 2D 와 다를 게 없다.
    const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 500);
    camera.position.set(width / 2, 26, height * 0.95);

    const renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    mount.appendChild(renderer.domElement);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.target.set(width / 2, 0, height / 2);
    controls.enableDamping = true;
    controls.maxPolarAngle = Math.PI / 2.1;  // 바닥 아래로는 못 내려가게
    controls.minDistance = 12;
    controls.maxDistance = 90;

    scene.add(new THREE.AmbientLight(0xffffff, 0.75));
    const key = new THREE.DirectionalLight(0xffffff, 1.1);
    key.position.set(width, 30, height);
    scene.add(key);

    const floor = new THREE.Mesh(
      new THREE.PlaneGeometry(width, height),
      new THREE.MeshStandardMaterial({ color: COLORS.floor }),
    );
    floor.rotation.x = -Math.PI / 2;
    floor.position.set(width / 2, 0, height / 2);
    scene.add(floor);

    const gridSize = Math.max(width, height);
    const grid = new THREE.GridHelper(gridSize, gridSize / 5, COLORS.grid, COLORS.grid);
    grid.position.set(width / 2, 0.01, height / 2);
    scene.add(grid);

    const nodeGroup = new THREE.Group();
    scene.add(nodeGroup);
    nodeGroupRef.current = nodeGroup;

    const routeGroup = new THREE.Group();
    scene.add(routeGroup);
    routeGroupRef.current = routeGroup;

    const resize = () => {
      const { clientWidth, clientHeight } = mount;
      if (clientWidth === 0 || clientHeight === 0) return;
      camera.aspect = clientWidth / clientHeight;
      camera.updateProjectionMatrix();
      renderer.setSize(clientWidth, clientHeight);
    };
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(mount);

    let frame = 0;
    const animate = () => {
      frame = requestAnimationFrame(animate);
      controls.update();
      renderer.render(scene, camera);
    };
    animate();

    const meshes = robotMeshes.current;
    const equip = equipmentMeshes.current;

    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      controls.dispose();
      scene.traverse((obj) => {
        if (obj instanceof THREE.Mesh) {
          obj.geometry.dispose();
          const material = obj.material;
          if (Array.isArray(material)) material.forEach((m) => m.dispose());
          else material.dispose();
        }
      });
      renderer.dispose();
      mount.removeChild(renderer.domElement);
      meshes.clear();
      equip.clear();
      sceneRef.current = null;
    };
  }, [width, height]);

  // 노드는 바뀌는 일이 드물어 따로 갱신한다.
  useEffect(() => {
    const group = nodeGroupRef.current;
    if (!group) return;

    group.clear();
    for (const node of nodes) {
      const [wx, wz] = toWorld(node.x, node.y, height);
      const kind = node.kind.toLowerCase() as 'pick' | 'drop' | 'waypoint';

      const disc = new THREE.Mesh(
        new THREE.CylinderGeometry(NODE_RADIUS, NODE_RADIUS, 0.06, 20),
        new THREE.MeshStandardMaterial({ color: COLORS[kind] ?? COLORS.waypoint }),
      );
      disc.position.set(wx, 0.03, wz);
      group.add(disc);

      const label = makeLabel(node.nodeId, '#7d8894');
      label.position.set(wx, 0.9, wz);
      label.scale.set(2.6, 0.65, 1);
      group.add(label);
    }
  }, [nodes, height]);

  // 로봇: 메시를 재사용하고 위치·색만 갱신한다.
  useEffect(() => {
    const scene = sceneRef.current;
    if (!scene) return;

    const seen = new Set<string>();

    for (const robot of robots) {
      const pos = robot.agvPosition;
      if (!pos) continue;
      seen.add(robot.serialNumber);

      let group = robotMeshes.current.get(robot.serialNumber);
      if (!group) {
        group = new THREE.Group();

        const body = new THREE.Mesh(
          new THREE.CylinderGeometry(ROBOT_RADIUS, ROBOT_RADIUS, ROBOT_HEIGHT, 18),
          new THREE.MeshStandardMaterial({ color: COLORS.idle }),
        );
        body.position.y = ROBOT_HEIGHT / 2;
        body.name = 'body';
        group.add(body);

        const nose = new THREE.Mesh(
          new THREE.ConeGeometry(0.22, 0.6, 12),
          new THREE.MeshStandardMaterial({ color: COLORS.idle }),
        );
        nose.name = 'nose';
        group.add(nose);

        const label = makeLabel(robot.serialNumber, '#c9d3de');
        label.position.y = ROBOT_HEIGHT + 0.9;
        group.add(label);

        scene.add(group);
        robotMeshes.current.set(robot.serialNumber, group);
      }

      const [wx, wz] = toWorld(pos.x, pos.y, height);
      group.position.set(wx, 0, wz);

      const color = robotColor(robot);
      const body = group.getObjectByName('body') as THREE.Mesh | undefined;
      const nose = group.getObjectByName('nose') as THREE.Mesh | undefined;
      if (body) (body.material as THREE.MeshStandardMaterial).color.setHex(color);
      if (nose) {
        (nose.material as THREE.MeshStandardMaterial).color.setHex(color);
        // z 를 뒤집었으므로 heading 의 z 성분도 부호가 반대다.
        nose.position.set(Math.cos(pos.theta) * 0.9, ROBOT_HEIGHT / 2, -Math.sin(pos.theta) * 0.9);
        nose.rotation.set(Math.PI / 2, 0, -pos.theta - Math.PI / 2);
      }
    }

    // 사라진 로봇 정리
    for (const [serial, group] of robotMeshes.current) {
      if (!seen.has(serial)) {
        scene.remove(group);
        robotMeshes.current.delete(serial);
      }
    }
  }, [robots, height]);

  // 남은 경로
  useEffect(() => {
    const group = routeGroupRef.current;
    if (!group) return;

    group.clear();
    for (const robot of robots) {
      const pos = robot.agvPosition;
      const remaining = robot.nodeStates ?? [];
      if (!pos || remaining.length === 0) continue;

      const points = [new THREE.Vector3(...insertY(toWorld(pos.x, pos.y, height)))];
      for (const state of remaining) {
        const node = nodes.find((n) => n.nodeId === state.nodeId);
        if (node) points.push(new THREE.Vector3(...insertY(toWorld(node.x, node.y, height))));
      }
      if (points.length < 2) continue;

      const line = new THREE.Line(
        new THREE.BufferGeometry().setFromPoints(points),
        new THREE.LineDashedMaterial({ color: COLORS.route, dashSize: 0.6, gapSize: 0.4 }),
      );
      line.computeLineDistances();
      group.add(line);
    }
  }, [robots, nodes, height]);

  // 설비
  useEffect(() => {
    const scene = sceneRef.current;
    if (!scene) return;

    for (const item of equipment) {
      if (item.x === undefined || item.y === undefined) continue;

      let mesh = equipmentMeshes.current.get(item.equipmentId);
      if (!mesh) {
        mesh = new THREE.Mesh(
          new THREE.BoxGeometry(EQUIPMENT_W, EQUIPMENT_H, EQUIPMENT_D),
          new THREE.MeshStandardMaterial({ color: COLORS.running }),
        );
        const label = makeLabel(item.equipmentId, '#0d1117');
        label.position.y = EQUIPMENT_H / 2 + 0.6;
        mesh.add(label);
        scene.add(mesh);
        equipmentMeshes.current.set(item.equipmentId, mesh);
      }

      const [wx, wz] = toWorld(item.x, item.y, height);
      mesh.position.set(wx, EQUIPMENT_H / 2, wz);
      (mesh.material as THREE.MeshStandardMaterial).color.setHex(equipmentColor(item.status));
    }
  }, [equipment, height]);

  return <div className="warehouse3d" ref={mountRef} />;
}
