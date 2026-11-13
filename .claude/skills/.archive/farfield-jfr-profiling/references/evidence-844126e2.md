before 97 GCs, 78 young: mean 17.2 ms, max 34.0, over 15 ms: 58
after 98 GCs, 78 young: mean 15.2 ms, max 30.6, over 15 ms: 35
total 256 GB
 53.8%  farfield-mesher
 18.5%  Worker-Main-N
 11.5%  Render thread
 10.2%  farfield-noise-preview
 26.7%  int[]  ThreadPoolExecutor.runWorker:1144 <- ThreadPoolExecutor$Worker.run:642 <- Thread.run:1583
  2.6%  java.lang.Long  Long.valueOf:1207 <- ThreadPoolExecutor.runWorker:1144 <- ThreadPoolExecutor$Worker.run:642 <- Thread.run:1583
Les deux correctifs ont réduit le boxing de `Long` à 2,6 % et les `Type[]` à 4 %, mais le volume total reste à environ 4 Go/s.