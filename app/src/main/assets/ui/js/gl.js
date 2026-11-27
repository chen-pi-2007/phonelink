/* 3D 主题（Three.js / WebGL1）：封面星尘、星云隧道、雨夜霓虹。
 * 省电约束：1 倍分辨率渲染、30 帧上限、暂停播放时 10 帧、约 8000 个粒子、叠加混合代替后期泛光、页面不可见时停止。
 * 节拍：读取 bg.js 的镜头冲击值 PL.bg.state.punch（同一套节拍事件），驱动粒子与相机。 */
(function () {
  var PL = window.PL;
  var GL_THEMES = { dust: 1, nebula: 1, rain: 1 };
  var renderer, scene, camera, cv, points, mat, mode = null, lastFrame = 0, t0 = performance.now();
  var coverImg = null, palette = [[0.5, 0.6, 1], [0.8, 0.5, 1], [0.4, 0.9, 1]];
  var N = 8000;

  // 每个粒子共用的着色器：位置在顶点着色器里按主题计算，只上传一次几何数据
  var VERT = [
    'attribute vec3 aSeed; attribute vec3 aColor;',
    'uniform float uTime; uniform float uBeat; uniform float uMode; uniform float uSize;',
    'varying vec3 vColor; varying float vAlpha;',
    'void main() {',
    '  vec3 p = position;',
    '  float t = uTime;',
    '  if (uMode < 0.5) {',                       // 封面星尘：封面像素铺成平面，按亮度有深度，节拍时沿 z 向外炸开
    '    p.z += sin(p.x * 3.0 + t * 0.9) * 0.05 + cos(p.y * 2.6 + t * 0.7) * 0.05;',
    '    p += normalize(vec3(p.xy, 0.6) + aSeed * 0.4) * uBeat * (0.25 + aSeed.z * 0.5);',
    '  } else if (uMode < 1.5) {',                // 星云隧道：粒子在圆柱面上迎面飞来
    '    float z = mod(p.z + t * (0.6 + uBeat * 2.4), 12.0) - 10.0;',
    '    float r = 1.1 + aSeed.x * 1.6 + sin(z * 0.6 + t) * 0.15;',
    '    float a = p.x + z * 0.12 + t * 0.05;',
    '    p = vec3(cos(a) * r, sin(a) * r * 0.85, z);',
    '  } else {',                                 // 雨夜霓虹：雨丝下落，节拍时加速
    '    p.y = mod(p.y - t * (1.6 + aSeed.x * 1.2) * (1.0 + uBeat * 0.8), 6.0) - 3.0;',
    '  }',
    '  vec4 mv = modelViewMatrix * vec4(p, 1.0);',
    '  gl_Position = projectionMatrix * mv;',
    '  float s = uSize * (0.6 + aSeed.y) * (1.0 + uBeat * 0.6);',
    '  gl_PointSize = s * (3.0 / -mv.z);',
    '  vColor = aColor;',
    '  vAlpha = clamp(1.4 - (-mv.z) * 0.09, 0.0, 1.0) * (0.32 + uBeat * 0.4);',
    '}'
  ].join('\n');
  var FRAG = [
    'uniform float uRain;',
    'varying vec3 vColor; varying float vAlpha;',
    'void main() {',
    '  vec2 c = gl_PointCoord - 0.5;',
    '  if (uRain > 0.5) c.x *= 4.0;',               // 雨丝：竖向拉长
    '  float d = length(c);',
    '  if (d > 0.5) discard;',
    '  float glow = smoothstep(0.5, 0.0, d);',
    '  gl_FragColor = vec4(vColor * (0.6 + glow), glow * glow * vAlpha);',
    '}'
  ].join('\n');

  function init() {
    if (!window.THREE || renderer) return !!renderer;
    cv = document.getElementById('gl');
    try {
      renderer = new THREE.WebGLRenderer({ canvas: cv, antialias: false, alpha: false, powerPreference: 'low-power' });
    } catch (e) { console.warn('WebGL 不可用', e); return false; }
    renderer.setPixelRatio(1); // 省电关键：按 CSS 像素渲染
    renderer.setSize(innerWidth, innerHeight, false);
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(60, innerWidth / innerHeight, 0.1, 50);
    camera.position.set(0, 0, 4);
    addEventListener('resize', function () {
      renderer.setSize(innerWidth, innerHeight, false);
      camera.aspect = innerWidth / innerHeight; camera.updateProjectionMatrix();
    });
    mat = new THREE.ShaderMaterial({
      vertexShader: VERT, fragmentShader: FRAG, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
      uniforms: { uTime: { value: 0 }, uBeat: { value: 0 }, uMode: { value: 0 }, uSize: { value: 9 }, uRain: { value: 0 } }
    });
    requestAnimationFrame(frame);
    return true;
  }

  function rand(seed) { var x = Math.sin(seed * 12.9898) * 43758.5453; return x - Math.floor(x); }

  /** 按主题生成粒子几何（只在切主题或换封面时做一次） */
  function build(m) {
    if (points) { scene.remove(points); points.geometry.dispose(); }
    var pos = new Float32Array(N * 3), seed = new Float32Array(N * 3), col = new Float32Array(N * 3);
    var px = null, side = 0;
    if (m === 'dust' && coverImg) {
      side = Math.floor(Math.sqrt(N));
      var c = document.createElement('canvas'); c.width = c.height = side;
      var g = c.getContext('2d'); g.drawImage(coverImg, 0, 0, side, side);
      px = g.getImageData(0, 0, side, side).data;
    }
    for (var i = 0; i < N; i++) {
      var a = rand(i + 1), b = rand(i + 7.3), d = rand(i + 3.1);
      seed[i * 3] = a; seed[i * 3 + 1] = b; seed[i * 3 + 2] = d;
      var pal = palette[i % 3], r = pal[0], gg = pal[1], bb = pal[2];
      if (m === 'dust') {
        var ix = side ? i % side : 0, iy = side ? Math.floor(i / side) % side : 0;
        var x = side ? (ix / side - 0.5) * 2.6 : (a - 0.5) * 2.6, y = side ? (0.5 - iy / side) * 2.6 : (b - 0.5) * 2.6;
        var lum = 0.5;
        if (px) { var k = (iy * side + ix) * 4; r = px[k] / 255; gg = px[k + 1] / 255; bb = px[k + 2] / 255; lum = (r + gg + bb) / 3; }
        pos[i * 3] = x + (a - 0.5) * 0.02; pos[i * 3 + 1] = y + (b - 0.5) * 0.02; pos[i * 3 + 2] = (lum - 0.5) * 0.5;
      } else if (m === 'nebula') {
        pos[i * 3] = a * Math.PI * 2; pos[i * 3 + 1] = 0; pos[i * 3 + 2] = d * 12;
      } else {
        pos[i * 3] = (a - 0.5) * 7; pos[i * 3 + 1] = (b - 0.5) * 6; pos[i * 3 + 2] = -d * 6;
        if (i % 7 === 0) { r = 1; gg = 0.25 + d * 0.3; bb = 0.8; } // 少量霓虹粉
      }
      col[i * 3] = r; col[i * 3 + 1] = gg; col[i * 3 + 2] = bb;
    }
    var geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('aSeed', new THREE.BufferAttribute(seed, 3));
    geo.setAttribute('aColor', new THREE.BufferAttribute(col, 3));
    points = new THREE.Points(geo, mat);
    points.frustumCulled = false;
    scene.add(points);
    mat.uniforms.uMode.value = m === 'dust' ? 0 : m === 'nebula' ? 1 : 2;
    mat.uniforms.uRain.value = m === 'rain' ? 1 : 0;
    mat.uniforms.uSize.value = m === 'dust' ? 7 : m === 'nebula' ? 9 : 14;
    scene.background = new THREE.Color(m === 'rain' ? 0x05040c : 0x03050a);
  }

  function frame(now) {
    var playing = document.body.classList.contains('playing');
    // 播放 30 帧、暂停 10 帧、非 3D 主题或后台时 1 次/秒检查一下
    setTimeout(function () { requestAnimationFrame(frame); }, !mode || document.hidden ? 1000 : playing ? 33 : 100);
    if (!mode || document.hidden) return;
    lastFrame = now;
    var st = (PL.bg && PL.bg.state) || { punch: 0, glow: 0 };
    var beat = Math.min(1, st.punch * 1.2 + st.glow * 0.3);
    var t = (now - t0) / 1000;
    mat.uniforms.uTime.value = t;
    mat.uniforms.uBeat.value = beat;
    // 相机：慢速环绕漂移 + 节拍推近（与 2D 主题同一套节拍事件）
    var base = mode === 'nebula' ? 0.6 : 4;
    camera.position.x = Math.sin(t * 0.08) * (mode === 'dust' ? 0.9 : 0.3);
    camera.position.y = Math.sin(t * 0.06 + 1) * (mode === 'dust' ? 0.5 : 0.2);
    camera.position.z = base - st.punch * (mode === 'dust' ? 0.6 : 0.3);
    camera.lookAt(0, 0, mode === 'nebula' ? -5 : 0);
    if (mode === 'rain') camera.rotation.z = Math.sin(t * 0.05) * 0.03;
    renderer.render(scene, camera);
  }

  function apply(theme) {
    var on = !!GL_THEMES[theme];
    document.body.classList.toggle('gl-on', on);
    if (!on) { mode = null; return; }
    if (!init()) { PL.setTheme('flow'); return; } // 不支持 WebGL 时退回 2D
    if (mode !== theme) { mode = theme; build(theme); }
  }

  PL.gl = {
    isGl: function (t) { return !!GL_THEMES[t]; },
    setCover: function (img, pal) {
      coverImg = img;
      if (pal) palette = pal.map(function (c) { return [c[0] / 160, c[1] / 160, c[2] / 160]; });
      if (mode) build(mode);
    }
  };
  PL.on('_theme', apply);
  document.addEventListener('DOMContentLoaded', function () { if (PL.theme) apply(PL.theme); });
})();
