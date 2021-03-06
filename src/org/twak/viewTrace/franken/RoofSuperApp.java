package org.twak.viewTrace.franken;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.vecmath.Point2d;
import javax.vecmath.Point3d;

import org.twak.camp.Output.Face;
import org.twak.camp.Tag;
import org.twak.tweed.Tweed;
import org.twak.tweed.gen.Pointz;
import org.twak.tweed.gen.SuperEdge;
import org.twak.tweed.gen.skel.MiniRoof;
import org.twak.tweed.gen.skel.RoofTag;
import org.twak.utils.Filez;
import org.twak.utils.Line;
import org.twak.utils.collections.Loop;
import org.twak.utils.collections.Loopable;
import org.twak.utils.collections.Loopz;
import org.twak.utils.collections.MultiMap;
import org.twak.utils.geom.DRectangle;
import org.twak.utils.geom.HalfMesh2.HalfEdge;
import org.twak.utils.ui.Colourz;
import org.twak.viewTrace.facades.FRect;
import org.twak.viewTrace.facades.GreebleHelper;
import org.twak.viewTrace.facades.MiniFacade;
import org.twak.viewTrace.facades.MiniFacade.Feature;
import org.twak.viewTrace.facades.NormSpecGen;

public class RoofSuperApp extends SuperSuper <MiniRoof> {

	public Map<Tag, String> textures = null;

	MiniRoof mr;
	
	public RoofSuperApp( MiniRoof mr) {
		
		super();
		
		this.scale = 180;
		this.mr = mr;
	}

	public RoofSuperApp( RoofSuperApp o ) {

		super( (SuperSuper) o );

		this.scale = 180;
		this.mr = o.mr;
	}

	@Override
	public App copy() {
		return new RoofSuperApp( this );
	}

	@Override
	public void setTexture( FacState<MiniRoof> state, BufferedImage cropped ) {
		
		NormSpecGen ns = new NormSpecGen( cropped, null, null);
		BufferedImage[] maps = new BufferedImage[] { cropped, ns.norm, ns.spec};
		
		String fileName = "scratch/" + UUID.randomUUID() +".png";

		try {
			ImageIO.write( maps[0], "png", new File(Tweed.DATA + "/" +fileName ) );
			ImageIO.write( maps[1], "png", new File(Tweed.DATA + "/" + Filez.extTo( fileName, "_norm.png" ) ) );
			ImageIO.write( maps[2], "png", new File(Tweed.DATA + "/" + Filez.extTo( fileName, "_spec.png" ) )  );
		} catch ( IOException e1 ) {
			e1.printStackTrace();
		}
		
		if (textures == null)
			textures = new HashMap<>();
		
	    state.mf.roofTexApp.textureUVs = TextureUVs.Zero_One;
		textures.put (state.tag, fileName );
	}
	
	private static class TwoRects {
		
		public DRectangle a, b;
		int res;
		
		public TwoRects (DRectangle a, DRectangle b, int res) {
			this.a = a;
			this.b = b;
			this.res = res;
		}
		
		public Point2d transform (Point2d in) {
			Point2d out = b.transform( a.normalize( in ) );
			out.set( out.x, res - out.y);
			return out;
		}
		
		public Point2d transform (Point3d in) {
			
			Point2d pt2 =Pointz.to2XY( in );
			
			Point2d out =  b.transform( a.normalize( pt2 ) );
			
			out.set( out.x, res - out.y);
			
			return out;
		}

		public Loop<Point2d> tranform( Loop<Point2d> verticalPts ) {
			
			return verticalPts.singleton().new Map<Point2d>() {
				@Override
				public Point2d map( Loopable<Point2d> input ) {
					return transform (input.get());
				}
			}.run().get(0);
		}
 	}
	
	@Override
	public void drawCoarse( MultiMap<MiniRoof, FacState> todo ) throws IOException {
		
		RoofTexApp rta = mr.roofTexApp;
		
		BufferedImage src = ImageIO.read( Tweed.toWorkspace( rta.coarse ) );
		
		NetInfo ni = NetInfo.get( this );
		
		int count = 0;
		
		for ( Loop<Point2d> verticalPts : mr.getAllFaces() ) {
			
			TwoRects toPix = new TwoRects( rta.textureRect, new DRectangle(src.getWidth(), src.getHeight()), ni.resolution );
			
			Loop<Point2d> pixPts = toPix.tranform( verticalPts );
			
			Color mean = Color.darkGray;
			mean = meanColor( src, mean, pixPts );
			
			Face origin = mr.origins.get( verticalPts );
			
			if (origin == null)
				continue;
			
			RoofTag rt = (RoofTag) GreebleHelper.getTag( origin.profile, RoofTag.class );
			
			Point2d start = toPix.transform( origin.edge.start ), 
					end   = toPix.transform( origin.edge.end );
			
			Line startEnd = new Line (start, end);
			
			AffineTransform 
					toOrigin  = AffineTransform.getTranslateInstance ( -start.x , -start.y ),
					rot       = AffineTransform.getRotateInstance    ( -startEnd.aTan2() ),
					deslope   = AffineTransform.getScaleInstance     ( 1, 1 / Math.cos ( origin.edge.getAngle() ) );
			
			AffineTransform t = AffineTransform.getTranslateInstance( 0, 0 ); 
			
			double[] bounds    = Loopz.minMax2d( Loopz.transform( verticalPts, rot ) ); // bad location, but scale-in-meters.
			
			t.preConcatenate( toOrigin );
			t.preConcatenate( rot );
			t.preConcatenate( deslope );
			
			AffineTransform geom2Big = new AffineTransform( t );
			
			double[] pixBounds = Loopz.minMax2d( Loopz.transform( pixPts, geom2Big ) );
			
			
			int 
				outWidth  =   (int) Math.ceil ( ( (bounds[1] - bounds[0] ) * scale ) / tileWidth ) * tileWidth, // round to exact tile multiples
				outHeight =   (int) Math.ceil ( ( (bounds[3] - bounds[2] ) * scale ) / tileWidth ) * tileWidth;
				
			BufferedImage bigCoarse = new BufferedImage(
					outWidth  + overlap * 2,
					outHeight + overlap * 2, BufferedImage.TYPE_3BYTE_BGR );

			Graphics2D g = bigCoarse.createGraphics();
			
			g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC );
			g.setRenderingHint( RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY );
	
			t.preConcatenate( AffineTransform.getTranslateInstance ( - pixBounds[0], 0 ));
			t.preConcatenate( AffineTransform.getScaleInstance ( outWidth / (pixBounds[1] - pixBounds[0] ), outHeight / (pixBounds[3] - pixBounds[2] ) ) );
			t.preConcatenate( AffineTransform.getTranslateInstance ( overlap, outHeight + overlap ) );
			
			g.setTransform( t );
			
			g.drawImage (src, 0, -256, null);

			
			if ( true ) { // pad edges

				g.setColor( mean );
				g.setStroke( new BasicStroke( overlap / 8 ) );

				for ( Loopable<Point2d> lp : pixPts.loopableIterator() )  // flashing
					g.drawLine( (int) lp.get().x, (int) lp.get().y, (int) lp.next.get().x, (int) lp.next.get().y );
				
				g.setColor( Colourz.transparent( mean, 128 ) );
				
				for (HalfEdge e : mr.superFace) { // dormers
					MiniFacade mf = ((SuperEdge)e).toEdit; 
					for (FRect f : mf.featureGen.getRects( Feature.WINDOW )) {
						
						PanesLabelApp pla = f.panesLabelApp;
						
						if ( pla.coveringRoof != null) {
							
							int c = 0;
							
							for (Loopable <Point2d> pt : toPix.tranform( pla.coveringRoof ).loopableIterator() ) 
								if (c ++ != 3)
									g.drawLine( (int) pt.get().x, (int) pt.get().y, (int) pt.getNext().get().x, (int) pt.getNext().get().y );
						}
					}
				}
			}
			
			g.dispose();
			
			FacState state = new FacState( bigCoarse, mr, new DRectangle(0,0,bounds[1] - bounds[0], bounds[3] - bounds[2] ), rt );
			
			for (int x =0; x <= outWidth / tileWidth; x ++)
				for (int y =0; y <= outHeight / tileWidth; y ++)
					state.nextTiles.add( new TileState( state, x, y ) );
	
			todo.put( mr, state );
		}
	}

	private Color meanColor( BufferedImage src, Color def, Loop<Point2d> pixPts ) {

		int[] meanCol = new int[3];
		int count2 = 0;
		
		double[] minMax = Loopz.minMax2d( pixPts );
		
		DRectangle bounds = new DRectangle( src.getWidth(), src.getHeight() );
		
		for ( int i = 0; i < 300; i++ ) {

			int 
				x = (int) (( Math.random() * (minMax[1] - minMax[0]) ) + minMax[0] ), 
				y = (int) (( Math.random() * (minMax[3] - minMax[2]) ) + minMax[2] ); 
			
			
			if ( Loopz.inside( new Point2d( x, y ), pixPts ) && bounds.contains(x, y + 256) ) {
				
				int rgb = src.getRGB( x,  y + 256 );
				
				count2++;
				int[] rgbs = Colourz.toComp( rgb );
				for ( int j = 0; j < 3; j++ )
					meanCol[ j ] += rgbs[ j ];
			}
		}
		if ( count2 > 0 ) {
			for ( int j = 0; j < 3; j++ )
				meanCol[ j ] /= count2;
			return Colourz.to3( meanCol ).darker();
		}
		return def;
	}

	@Override
	public App getUp( ) {
		return mr.roofTexApp;
	}

}
