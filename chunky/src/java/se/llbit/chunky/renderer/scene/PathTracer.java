/* Copyright (c) 2013 Jesper Öqvist <jesper@llbit.se>
 *
 * This file is part of Chunky.
 *
 * Chunky is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Chunky is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Chunky.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.llbit.chunky.renderer.scene;

import java.util.Random;

import org.apache.commons.math3.util.FastMath;

import se.llbit.chunky.model.WaterModel;
import se.llbit.chunky.renderer.WorkerState;
import se.llbit.chunky.world.Block;
import se.llbit.math.QuickMath;
import se.llbit.math.Ray;
import se.llbit.math.Vector3d;
import se.llbit.math.Vector4d;

/**
 * Static methods for path tracing
 *
 * @author Jesper Öqvist <jesper@llbit.se>
 */
public class PathTracer {

	/**
	 * Path trace the ray
	 * @param scene
	 * @param state
	 */
	public static final void pathTrace(Scene scene, WorkerState state) {
		pathTrace(scene, state.ray, state, 1, true);
	}

	/**
	 * Path trace the ray in this scene
	 * @param scene
	 * @param state
	 * @param addEmitted
	 * @param first
	 */
	public static final void pathTrace(Scene scene, Ray ray, WorkerState state,
			int addEmitted, boolean first) {

		Random random = state.random;
		Ray reflected = state.rayPool.get();
		Ray transmitted = state.rayPool.get();
		Ray refracted = state.rayPool.get();
		Vector3d ox = state.vectorPool.get(ray.x);
		Vector3d od = state.vectorPool.get(ray.d);
		double s = 0;

		while (true) {

			if (!RayTracer.nextIntersection(scene, ray, state)) {
				if (ray.depth == 0) {
					// direct sky hit
					scene.sky.getSkyColorInterpolated(ray, scene.waterHeight > 0);

				} else if (ray.specular) {
					// sky color
					scene.sky.getSkySpecularColor(ray, scene.waterHeight > 0);
				} else {
					scene.sky.getSkyDiffuseColor(ray, scene.waterHeight > 0);
				}
				break;
			}

			double pSpecular = 0;

			Block currentBlock = ray.getCurrentBlock();
			Block prevBlock = ray.getPrevBlock();

			if (!scene.stillWater && ray.n.y != 0 &&
					((currentBlock == Block.WATER && prevBlock == Block.AIR) ||
					(currentBlock == Block.AIR && prevBlock == Block.WATER))) {

				WaterModel.doWaterDisplacement(ray);

				if (currentBlock == Block.AIR) {
					ray.n.y = -ray.n.y;
				}
			}

			if (currentBlock.isShiny) {
				if (currentBlock == Block.WATER) {
					pSpecular = Scene.WATER_SPECULAR;
				} else {
					pSpecular = Scene.SPECULAR_COEFF;
				}
			}

			double pDiffuse = ray.color.w;

			float n1 = prevBlock.ior;
			float n2 = currentBlock.ior;

			if (pDiffuse + pSpecular < Ray.EPSILON && n1 == n2)
				continue;

			if (first) {
				s = ray.distance;
				first = false;
			}

			if (currentBlock.isShiny &&
					random.nextDouble() < pSpecular) {

				reflected.specularReflection(ray);

				if (!scene.kill(reflected, random)) {
					pathTrace(scene, reflected, state, 1, false);
					if (reflected.hit) {
						ray.color.x *= reflected.color.x;
						ray.color.y *= reflected.color.y;
						ray.color.z *= reflected.color.z;
						ray.hit = true;
					}
				}

			} else {

				if (random.nextDouble() < pDiffuse) {

					reflected.set(ray);
					if (!scene.kill(reflected, random)) {

						double emittance = 0;

						if (scene.emittersEnabled && currentBlock.isEmitter) {

							emittance = addEmitted;
							ray.emittance.x = ray.color.x * ray.color.x *
									currentBlock.emittance * scene.emitterIntensity;
							ray.emittance.y = ray.color.y * ray.color.y *
									currentBlock.emittance * scene.emitterIntensity;
							ray.emittance.z = ray.color.z * ray.color.z *
									currentBlock.emittance * scene.emitterIntensity;
							ray.hit = true;
						}

						if (scene.sunEnabled) {
							scene.sun.getRandomSunDirection(reflected, random, state.vectorPool);

							double directLightR = 0;
							double directLightG = 0;
							double directLightB = 0;

							boolean frontLight = reflected.d.dot(ray.n) > 0;

							if (frontLight || (currentBlock.subSurfaceScattering &&
									random.nextDouble() < Scene.fSubSurface)) {

								if (!frontLight) {
									reflected.x.scaleAdd(-Ray.OFFSET, ray.n);
								}

								reflected.currentMaterial = ray.prevMaterial;

								getDirectLightAttenuation(scene, reflected, state);

								Vector4d attenuation = state.attenuation;
								if (attenuation.w > 0) {
									double mult = QuickMath.abs(reflected.d.dot(ray.n));
									directLightR = attenuation.x*attenuation.w * mult;
									directLightG = attenuation.y*attenuation.w * mult;
									directLightB = attenuation.z*attenuation.w * mult;
									ray.hit = true;
								}
							}

							reflected.diffuseReflection(ray, random);
							pathTrace(scene, reflected, state, 0, false);
							ray.hit = ray.hit || reflected.hit;
							if (ray.hit) {
								ray.color.x = ray.color.x
									* (emittance + directLightR * scene.sun.emittance.x
										+ (reflected.color.x + reflected.emittance.x));
								ray.color.y = ray.color.y
									* (emittance + directLightG * scene.sun.emittance.y
										+ (reflected.color.y + reflected.emittance.y));
								ray.color.z = ray.color.z
									* (emittance + directLightB * scene.sun.emittance.z
										+ (reflected.color.z + reflected.emittance.z));
							}

						} else {
							reflected.diffuseReflection(ray, random);

							pathTrace(scene, reflected, state, 0, false);
							ray.hit = ray.hit || reflected.hit;
							if (ray.hit) {
								ray.color.x = ray.color.x
									* (emittance + (reflected.color.x + reflected.emittance.x));
								ray.color.y = ray.color.y
									* (emittance + (reflected.color.y + reflected.emittance.y));
								ray.color.z = ray.color.z
									* (emittance + (reflected.color.z + reflected.emittance.z));
							}
						}
					}
				} else if (n1 != n2) {

					boolean doRefraction =
							currentBlock == Block.WATER ||
							prevBlock == Block.WATER ||
							currentBlock == Block.ICE ||
							prevBlock == Block.ICE;

					// refraction
					float n1n2 = n1 / n2;
					double cosTheta = - ray.n.dot(ray.d);
					double radicand = 1 - n1n2*n1n2 * (1 - cosTheta*cosTheta);
					if (doRefraction && radicand < Ray.EPSILON) {
						// total internal reflection
						reflected.specularReflection(ray);
						if (!scene.kill(reflected, random)) {
							pathTrace(scene, reflected, state, 1, false);
							if (reflected.hit) {

								ray.color.x = reflected.color.x;
								ray.color.y = reflected.color.y;
								ray.color.z = reflected.color.z;
								ray.hit = true;
							}
						}
					} else {
						refracted.set(ray);
						if (!scene.kill(refracted, random)) {

							// Calculate angle-dependent reflectance using
							// Fresnel equation approximation
							// R(theta) = R0 + (1 - R0) * (1 - cos(theta))^5
							float a = (n1n2 - 1);
							float b = (n1n2 + 1);
							double R0 = a*a/(b*b);
							double c = 1 - cosTheta;
							double Rtheta = R0 + (1-R0) * c*c*c*c*c;

							if (random.nextDouble() < Rtheta) {
								reflected.specularReflection(ray);
								pathTrace(scene, reflected, state, 1, false);
								if (reflected.hit) {
									ray.color.x = reflected.color.x;
									ray.color.y = reflected.color.y;
									ray.color.z = reflected.color.z;
									ray.hit = true;
								}
							} else {
								if (doRefraction) {

									double t2 = FastMath.sqrt(radicand);
									if (cosTheta > 0) {
										refracted.d.x = n1n2*ray.d.x + (n1n2*cosTheta - t2)*ray.n.x;
										refracted.d.y = n1n2*ray.d.y + (n1n2*cosTheta - t2)*ray.n.y;
										refracted.d.z = n1n2*ray.d.z + (n1n2*cosTheta - t2)*ray.n.z;
									} else {
										refracted.d.x = n1n2*ray.d.x - (-n1n2*cosTheta - t2)*ray.n.x;
										refracted.d.y = n1n2*ray.d.y - (-n1n2*cosTheta - t2)*ray.n.y;
										refracted.d.z = n1n2*ray.d.z - (-n1n2*cosTheta - t2)*ray.n.z;
									}

									refracted.d.normalize();

									refracted.x.scaleAdd(Ray.OFFSET, refracted.d);
								}

								pathTrace(scene, refracted, state, 1, false);
								if (refracted.hit) {
									ray.color.x = ray.color.x * pDiffuse + (1-pDiffuse);
									ray.color.y = ray.color.y * pDiffuse + (1-pDiffuse);
									ray.color.z = ray.color.z * pDiffuse + (1-pDiffuse);
									ray.color.x *= refracted.color.x;
									ray.color.y *= refracted.color.y;
									ray.color.z *= refracted.color.z;
									ray.hit = true;
								}
							}
						}
					}

				} else {

					transmitted.set(ray);
					transmitted.x.scaleAdd(Ray.OFFSET, transmitted.d);

					pathTrace(scene, transmitted, state, 1, false);
					if (transmitted.hit) {
						ray.color.x = ray.color.x * pDiffuse + (1-pDiffuse);
						ray.color.y = ray.color.y * pDiffuse + (1-pDiffuse);
						ray.color.z = ray.color.z * pDiffuse + (1-pDiffuse);
						ray.color.x *= transmitted.color.x;
						ray.color.y *= transmitted.color.y;
						ray.color.z *= transmitted.color.z;
						ray.hit = true;
					}
				}
			}

			// do water fog
			if (!scene.clearWater && prevBlock == Block.WATER) {
				double a = ray.distance / scene.waterVisibility;
				double attenuation = 1 - QuickMath.min(1, a*a);
				ray.color.scale(attenuation);
				/*ray.color.x *= attenuation;
				ray.color.y *= attenuation;
				ray.color.z *= attenuation;
				float[] wc = Texture.water.getAvgColorLinear();
				ray.color.x += (1-attenuation) * wc[0];
				ray.color.y += (1-attenuation) * wc[1];
				ray.color.z += (1-attenuation) * wc[2];
				ray.color.w = attenuation;*/
				ray.hit = true;
			}

			break;
		}
		if (!ray.hit) {
			ray.color.set(0, 0, 0, 1);
			if (first)
				s = ray.distance;
		}

		if (s > 0) {

			if (scene.atmosphereEnabled) {
				double Fex = scene.sun.extinction(s);
				ray.color.x *= Fex;
				ray.color.y *= Fex;
				ray.color.z *= Fex;

				if (!scene.volumetricFogEnabled) {
					double Fin = scene.sun.inscatter(Fex, scene.sun.theta(ray.d));

					ray.color.x += Fin * scene.sun.emittance.x * scene.sun.getIntensity();
					ray.color.y += Fin * scene.sun.emittance.y * scene.sun.getIntensity();
					ray.color.z += Fin * scene.sun.emittance.z * scene.sun.getIntensity();
				}
			}

			if (scene.volumetricFogEnabled) {
				s = (s - Ray.OFFSET) * random.nextDouble();

				reflected.x.scaleAdd(s, od, ox);
				scene.sun.getRandomSunDirection(reflected, random, state.vectorPool);
				reflected.currentMaterial = 0;

				getDirectLightAttenuation(scene, reflected, state);
				Vector4d attenuation = state.attenuation;

				double Fex = scene.sun.extinction(s);
				double Fin = scene.sun.inscatter(Fex, scene.sun.theta(ray.d));

				ray.color.x += 50 * attenuation.x*attenuation.w * Fin * scene.sun.emittance.x * scene.sun.getIntensity();
				ray.color.y += 50 * attenuation.y*attenuation.w * Fin * scene.sun.emittance.y * scene.sun.getIntensity();
				ray.color.z += 50 * attenuation.z*attenuation.w * Fin * scene.sun.emittance.z * scene.sun.getIntensity();
			}
		}

		state.rayPool.dispose(reflected);
		state.rayPool.dispose(transmitted);
		state.rayPool.dispose(refracted);
		state.vectorPool.dispose(ox);
		state.vectorPool.dispose(od);
	}

	/**
	 * Calculate direct lighting attenuation
	 * @param scene
	 * @param ray
	 * @param state
	 */
	public static final void getDirectLightAttenuation(Scene scene, Ray ray,
			WorkerState state) {

		Vector4d attenuation = state.attenuation;
		attenuation.x = 1;
		attenuation.y = 1;
		attenuation.z = 1;
		attenuation.w = 1;
		while (attenuation.w > 0) {
			ray.x.scaleAdd(Ray.OFFSET, ray.d);
			if (!RayTracer.nextIntersection(scene, ray, state))
				break;
			double mult = 1 - ray.color.w;
			attenuation.x *= ray.color.x * mult + ray.color.w;
			attenuation.y *= ray.color.y * mult + ray.color.w;
			attenuation.z *= ray.color.z * mult + ray.color.w;
			attenuation.w *= mult;
			if (!scene.clearWater && ray.getPrevBlock() == Block.WATER) {
				double a = ray.distance / scene.waterVisibility;
				attenuation.w *= 1 - QuickMath.min(1, a*a);
			}
		}
	}

}
