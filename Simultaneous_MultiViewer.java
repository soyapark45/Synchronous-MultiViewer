import ij.*;
import ij.gui.*;
import ij.measure.*;
import ij.process.*;
import ij.plugin.*;
import ij.plugin.frame.*;
import ij.measure.Calibration;
import java.awt.*;
import java.awt.image.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import ij.io.*;
import java.util.*;
import java.text.*;
import java.util.Map.Entry;
import java.awt.BorderLayout;
import java.awt.Container;
import javax.swing.border.Border;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
/**
 * Massachusetts General Hospital / Vakoc group 
 * @author Soya Park <soya@kaist.ac.kr>
 * @date 07/14/2015
 *
 * Some part of source code is taken from a plug-in Orthogonal_View. 
 * 
**/

//give warning about stack size differ.

public class Simultaneous_MultiViewer implements PlugIn, MouseListener, MouseMotionListener, ImageListener, AdjustmentListener, ComponentListener, WindowListener {
    private Map<Integer, ImagePlus> imageMap;
    private Map<Integer, ImagePlus> xzMap, yzMap;

    private Map<Integer, ImageWindow> win;
    private Map<Integer, Point> primaryLocation;    

    private int cntImageOpen = 0 ;
    private boolean imageScaled = true,imageArranged = true;

	private boolean rgb;

    private Map<Integer, ImageProcessor> fp1, fp2;
    private int preViousWidth, preViousHeight;
    private int prevX, prevY, prevSlice;
    private boolean preViousOpened = false;

	private double ax, ay, az;

	private int xyX, xyY;
    private Calibration cal=null, cal_xz=new Calibration(), cal_yz=new Calibration();
    private double magnification = 1.0;
    private Color color = Roi.getColor();
    private double min, max;
    private Dimension screen = IJ.getScreenSize();

    private boolean rotateYZ = Prefs.rotateYZ;
    private boolean flipXZ = Prefs.flipXZ;
    private Point crossLoc;    

    /* For transmitting a translated cursor. */
    private class TCursor {
        Point p = new Point();
        int slice = -1;
    }

	public void run(String arg) {
        /* Before start, make sure user is using proper imageJ. */
        if(!checkVersion())
            return;

        crossLoc = new Point();

        imageMap = new HashMap<Integer, ImagePlus>();

        xzMap = new HashMap<Integer, ImagePlus>();
        yzMap = new HashMap<Integer, ImagePlus>();

        win = new HashMap<Integer, ImageWindow>();

        primaryLocation = new HashMap<Integer, Point>();
        fp1 = new HashMap<Integer, ImageProcessor>();
        fp2 = new HashMap<Integer, ImageProcessor>();

        SyncWindows s = new SyncWindows("Simutaneous Multi-viewer"){
            public Component buildWindowList() {
                ImagePlus img;
                ImageWindow iw;

                // get IDList from WindowManager
                int[] imageIDs = WindowManager.getIDList();       
                
                if(imageIDs != null) {
                    int size;
                    if (imageIDs.length < 10) {
                        size = imageIDs.length;
                    } else {
                        size = 10;
                    } 
                    
                    // Initialize window list and vector that maps list entries to window IDs.
                    wList = new java.awt.List(size, true);
                    vListMap = new Vector();

                    // Add Windows to list, select windows, that previously were selected
                    for(int n=0; n<imageIDs.length;++n) {
                        String title = WindowManager.getImage(imageIDs[n]).getTitle();
                        if(!"XZ".equals(title.substring(0, 2)) && !"YZ".equals(title.substring(0, 2)))
                            wList.add(title);

                        vListMap.addElement(new Integer(imageIDs[n]));
                        
                        if ( vwins != null && vwins.contains(new Integer(imageIDs[n])) ) {
                            wList.select(n);
                        }
                    }

                    // clean vector of selected images (vwins) from images that have been closed,
                    if (vwins != null && vwins.size() != 0) {
                        for (int n=0; n<vwins.size(); ++n) {
                            if(! vListMap.contains(vwins.elementAt(n))) {
                                vwins.removeElementAt(n);
                                n -= 1;
                            }
                        }
                    }

                    wList.addItemListener(this);
                    wList.addActionListener(this);
                    return (Component)wList;
                }
                else {
                    Label label = new Label("No windows to select.");
                    wList = null;
                    vListMap = null;
                    vwins = null;
                    return (Component)label;
                }
            }

            public Panel buildControlPanel() {
                Panel p = new Panel(new BorderLayout());

                // Checkbox: synchronize cursor
                cCursor = new Checkbox("Sync Cursor", true);
                p.add(cCursor);
                cCursor.setVisible(false);

                // Checkbox: propagate slice
                cSlice = new Checkbox("Sync z-Slices",true);
                p.add(cSlice);
                cSlice.setVisible(false);

                // Checkbox: synchronize channels (for hyperstacks)
                cChannel = new Checkbox("Sync Channels", true);
                p.add(cChannel);
                cChannel.setVisible(false);
                
                // Checkbox: synchronize time-frames (for hyperstacks)
                cFrame = new Checkbox("Sync t-Frames", true);
                p.add(cFrame);
                cFrame.setVisible(false);
                
                // Checkbox: image coordinates
                cCoords = new Checkbox("Image Coordinates", true);
                cCoords.addItemListener(this);
                p.add(cCoords);
                cCoords.setVisible(false);
         
                // Checkbox: image scaling (take pixel scale and offset into account)
                cScaling = new Checkbox("Image Scaling", false);
                cScaling.addItemListener(this);
                p.add(cScaling);
                cScaling.setVisible(false);

                // Synchronize all windows.
                bSyncAll = new Button("Start Simutaneous View");
                bSyncAll.addActionListener(this);
                p.add(bSyncAll);

                Label jlabel = new Label("Soya Park <soya@kaist.ac.kr> MGH / Vakoc group");
                jlabel.setFont(new Font("Arial",1,8));
                p.add(jlabel, BorderLayout.SOUTH);

                return p;
            }

            public void actionPerformed(ActionEvent e) {
                /* Show a warning msg so that user won't touch windows during the process. */
                bSyncAll.setLabel("Processing images..");

                super.actionPerformed(e);
                processImage();
                IJ.wait(500);
                
                bSyncAll.setLabel("Images are synced");
            }
        };
	}

    /**
    *   @method Put xz and yz images sticking to xy images 
    */
    private void arrangeWindows(int id) {
        ImagePlus imp = imageMap.get(id);
        ImageWindow xyWin = imp.getWindow();
        ImageCanvas canvas = xyWin.getCanvas();
        
        if (xyWin==null) return;
        Point loc = xyWin.getLocation();

        /* Locate image accordingly in order to avoid overlapping just for first time. */
        if (!imageArranged) {
            Point p = primaryLocation.get(id);
            xyWin.setLocation(p.x, p.y);

            imageArranged = true;
        }

        if (imageArranged || ((xyX!=loc.x)||(xyY!=loc.y))) {
            xyX =  loc.x;
            xyY =  loc.y;

            long start;
            if(yzMap.containsKey(id)) {
                ImagePlus yz_image = yzMap.get(id);
                ImageWindow yzWin =null;
                start = System.currentTimeMillis();
                while (yzWin==null && (System.currentTimeMillis()-start)<=2500L) {
                    yzWin = yz_image.getWindow();
                    if (yzWin==null) IJ.wait(50);
                }
                if (yzWin!=null)
                    yzWin.setLocation(xyX+xyWin.getWidth(), xyY);
            }

            if(xzMap.containsKey(id)) {
                ImagePlus xz_image = xzMap.get(id);
                ImageWindow xzWin =null;
                start = System.currentTimeMillis();


                while (xzWin==null && (System.currentTimeMillis()-start)<=2500L) {
                    xzWin = xz_image.getWindow();
                    if (xzWin==null) IJ.wait(50);
                }
                if (xzWin!=null)
                    xzWin.setLocation(xyX,xyY+xyWin.getHeight());
            }
            
        }
    }

    /**
    *   @method calibrate a given image with id
    */
    private void calibrate(int id) {
        String unit=cal.getUnit();
        double o_depth=cal.pixelDepth;
        double o_height=cal.pixelHeight;
        double o_width=cal.pixelWidth;

        if(yzMap.containsKey(id)) {
            ImagePlus yz_image = yzMap.get(id);
            cal_yz.setUnit(unit);

            if (rotateYZ) {
                cal_yz.pixelHeight=o_depth/az;
                cal_yz.pixelWidth=o_height;
            } else {
                cal_yz.pixelWidth=o_depth/az;
                cal_yz.pixelHeight=o_height;
            }
            yz_image.setCalibration(cal_yz);
        }

        if(xzMap.containsKey(id)) {
            ImagePlus xz_image = xzMap.get(id);

            cal_xz.setUnit(unit);
            cal_xz.pixelWidth=o_width;
            cal_xz.pixelHeight=o_depth/az;
            xz_image.setCalibration(cal_xz);
        }

    }

    /**
    *   @method create processors for a given image with id
    */
    boolean checkVersion() {
        String strVer = IJ.getFullVersion(); 
        double ver = Double.parseDouble(strVer.substring(0, 4));

        if(ver < 1.46) {
            IJ.showMessage("Your ImageJ is too old. You should have one with >1.45 in order to use Simutaneous Multi-viewer.");
            return false;
        }

        return true;
    }

    /**
    *   @method create processors for a given image with id
    */
    private boolean createProcessors(int id, ImageStack is) {
        ImageProcessor ip=is.getProcessor(1);
        int width= is.getWidth();
        int height=is.getHeight();
        int ds=is.getSize(); 
        double arat=1.0;//az/ax;
        double brat=1.0;//az/ay;
        int za=(int)(ds*arat);
        int zb=(int)(ds*brat);

        if (ip instanceof FloatProcessor) {
            fp1.put(id, new FloatProcessor(width,za));
            if (rotateYZ)
                fp2.put(id, new FloatProcessor(height,zb));
            else
                fp2.put(id, new FloatProcessor(zb,height));
            return true;
        }
        
        if (ip instanceof ByteProcessor) {
            fp1.put(id, new ByteProcessor(width,za));
            if (rotateYZ)
                fp2.put(id, new ByteProcessor(height,zb));
            else
                fp2.put(id, new ByteProcessor(zb,height));
            return true;
        }
        
        if (ip instanceof ShortProcessor) {
            fp1.put(id, new ShortProcessor(width,za));
            if (rotateYZ)
                fp2.put(id, new ShortProcessor(height,zb));
            else
                fp2.put(id, new ShortProcessor(zb,height));
            //IJ.log("createProcessors "+rotateYZ+"  "+height+"   "+zb+"  "+fp2);
            return true;
        }
        
        if (ip instanceof ColorProcessor) {
            fp1.put(id, new ColorProcessor(width,za));
            if (rotateYZ)
                fp2.put(id, new ColorProcessor(height,zb));
            else
                fp2.put(id, new ColorProcessor(zb,height));
            return true;
        }
        
        return false;
    }

    /**
    *   @method create xz and yz orthogonal images for given image.
    */
    void createOrthogonal(ImagePlus imp) {
        int id = imp.getID();

        imp.setSlice(1);

        ImageProcessor ip = imp.isHyperStack()?new ColorProcessor(imp.getImage()):imp.getProcessor();
        min = ip.getMin();
        max = ip.getMax();
        cal= imp.getCalibration();
        double calx=cal.pixelWidth;
        double caly=cal.pixelHeight;
        double calz=cal.pixelDepth;
        ax=1.0;
        ay=caly/calx;
        az=calz/calx;

        ImageWindow win = imp.getWindow();
        ImageCanvas canvas = win.getCanvas();
        //addListeners(canvas);
        magnification= canvas.getMagnification();
        imp.deleteRoi();

        Rectangle r = canvas.getSrcRect();
        crossLoc.x = r.x+r.width/2;
        crossLoc.y = r.y+r.height/2;

        ImageStack imageStack = imp.getStack();
        if (createProcessors(imp.getID(), imageStack)) {
            if (ip.isColorLut() || ip.isInvertedLut()) {
                ColorModel cm = ip.getColorModel();
                fp1.get(id).setColorModel(cm);
                fp2.get(id).setColorModel(cm);              
            }
        }
        
        xzMap.put(id, new ImagePlus());
        yzMap.put(id, new ImagePlus());

        updateViews(id,crossLoc);
    }

    void drawCross(ImagePlus imp, Point p, GeneralPath path) {
        int width=imp.getWidth();
        int height=imp.getHeight();
        float x = p.x;
        float y = p.y;
        path.moveTo(0f, y);
        path.lineTo(width, y);
        path.moveTo(x, 0f);
        path.lineTo(x, height); 
    }

    private TCursor getTranslateCursor(MouseEvent e) {
        TCursor tc = new TCursor();        
        ImageCanvas ic = (ImageCanvas) e.getSource();
        Iterator<Map.Entry<Integer, ImagePlus>> entries;
        ImagePlus imp = ((ImageWindow)ic.getParent()).getImagePlus();
        
        tc.p = crossLoc;

        /* If user clicked one of main image. */                
        if(imageMap.containsValue(imp)) {
            entries = imageMap.entrySet().iterator();

            while (entries.hasNext()) {
                Map.Entry<Integer, ImagePlus> entry = entries.next();
                ImageCanvas tempCanvas = entry.getValue().getCanvas();

                if(e.getSource() == tempCanvas) {
                    tc.p = tempCanvas.getCursorLoc();
                    break;
                }
            }
        }

        /* If user clicked one of xz_image. */
        else if(xzMap.containsValue(imp)) {
            entries = xzMap.entrySet().iterator();

            while (entries.hasNext()) {
                Map.Entry<Integer, ImagePlus> entry = entries.next();
                ImageCanvas tempCanvas = entry.getValue().getCanvas();

                if(e.getSource() == tempCanvas) {
                    tc.p.x = tempCanvas.getCursorLoc().x;
                    int pos = tempCanvas.getCursorLoc().y;
                    int z = (int)Math.round(pos/az);

                    ImagePlus impp = imageMap.get(entry.getKey());

                    tc.slice = flipXZ?impp.getNSlices()-z:z+1;
                    break;
                }
            }
        }

        /* If user clicked one of yz_image. */
        else if(yzMap.containsValue(imp)) {
            entries = yzMap.entrySet().iterator();

            while (entries.hasNext()) {
                Map.Entry<Integer, ImagePlus> entry = entries.next();
                ImageCanvas tempCanvas = entry.getValue().getCanvas();
                int pos;

                if(e.getSource() == tempCanvas) {
                    if (rotateYZ) {
                        tc.p.y = tempCanvas.getCursorLoc().x;
                        pos = tempCanvas.getCursorLoc().y;
                    } else {
                        tc.p.y = tempCanvas.getCursorLoc().y;
                        pos = tempCanvas.getCursorLoc().x;
                    }
                    int z = (int)Math.round(pos/az);

                    tc.slice = z+1;
                    break;
                }
            }
        }

        return tc;
    }

    /**
    *   @method let user opens 3 images at most. 
    */
    private void openImage() {
        String msg;
        for(int i = 0;i < 3;i++){
            if(i == 0) msg = "Choose your first file";
            else if(i == 1) msg = "Choose your second file";
            else msg = "Choose your third file";

            OpenDialog od = new OpenDialog(msg, null);  
            String dir = od.getDirectory();  
            if (null != dir) {  
                imageArranged = false;
                imageScaled = false;

                dir = dir.replace('\\', '/'); // Windows safe  
                if (!dir.endsWith("/")) dir += "/";  

                ImagePlus imp = new ImagePlus(dir + od.getFileName());
                imageMap.put(imp.getID(), imp);
                imp.show();

                IJ.wait(1000);

                ImageWindow w = imp.getWindow();
                win.put(imp.getID(), w);
                
                if (preViousOpened && (preViousWidth != w.getCanvas().getWidth() || preViousHeight != w.getCanvas().getHeight()))
                    IJ.showMessage("Size of images is different. The plug-in might not be functional well.");

                w.addComponentListener(this);

                preViousOpened = true;
                preViousWidth = w.getCanvas().getWidth();
                preViousHeight = w.getCanvas().getHeight();
            }
        }
    }

    public void processImage(){
        ImagePlus img = WindowManager.getCurrentImage();
  
        /* Let user open up the images through open dialog, if there's none of image is currently opened. */
        if(img == null)
            openImage();

        else {
            int[] imageIDs = WindowManager.getIDList();
            for(int n=0; n<imageIDs.length;++n) {
                ImagePlus imp = WindowManager.getImage(imageIDs[n]);
                imageMap.put(imageIDs[n], imp);
               
                ImageWindow w = imp.getWindow();
                win.put(imp.getID(), w);
                
                w.addComponentListener(this);
                w.addWindowListener (this);  
            }
        }
        
        Iterator<Map.Entry<Integer, ImagePlus>> entries1 = imageMap.entrySet().iterator();
        while (entries1.hasNext()) {
            Map.Entry<Integer, ImagePlus> entry = entries1.next();
            int id = entry.getKey();
            
            setPrimaryLocation(id);
        }

        Iterator<Map.Entry<Integer, ImagePlus>> entries = imageMap.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, ImagePlus> entry = entries.next();
            ImagePlus imp = entry.getValue();
            
            createOrthogonal(imp);
            imageArranged = false;

            /* Stick xz and yz to xy images right after they're created. */
            arrangeWindows(imp.getID());
            IJ.wait(500);
            resizeWindows(imp.getID());
            
            //arrangeWindows(imp.getID());
        }

        int[] imageIDs = WindowManager.getIDList();
        for(int n=0; n<imageIDs.length;++n) {
            ImagePlus imp = WindowManager.getImage(imageIDs[n]);
            ImageWindow w = imp.getWindow();
            w.getCanvas().addMouseListener(this);

            //w.getCanvas().addMouseMotionListener(this);
        }

        ImagePlus.addImageListener(this);   
    }

    /**
    *   @method resize window so that window fitting into screen. 
    */
    private void resizeWindows(int id) {
        ImagePlus imp = imageMap.get(id);
        ImagePlus xz_image = xzMap.get(id);

        /* Get height of the screen and main image. */
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();

        int width = gd.getDisplayMode().getWidth(); 
        int s_height = gd.getDisplayMode().getHeight(); //px of screen
        int i_height; //px of image
        int xz_height;

        ImageWindow win = imp.getWindow();
        ImageCanvas canvas = win.getCanvas();
        ImageCanvas xz_canvas = xz_image.getWindow().getCanvas();
        i_height = canvas.getHeight();
        xz_height = xz_canvas.getHeight(); 

        /* Choose magnification until images are fit into screen. */
        while(canvas.getHeight() + xz_height*canvas.getMagnification() > s_height) {
            IJ.run(imp, "Out [-]", "");

            IJ.wait(400);
        }

        IJ.run(imp, "Out [-]", "");

        imageScaled = true;
    }   

    /**
    *   @method set initial location of each images. 
    */
    void setPrimaryLocation(int id){
        ImagePlus imp = imageMap.get(id);
        Point loc = new Point();

        loc.x = 50 + cntImageOpen * 400;
        loc.y = cntImageOpen * 100;
        primaryLocation.put(id, loc);

        cntImageOpen ++;
    }

    private void updateCrosses(int x, int y, double arat, double brat) {
        Iterator<Map.Entry<Integer, ImagePlus>> entries;
        entries = imageMap.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, ImagePlus> entry = entries.next();
            ImagePlus imp = entry.getValue();

            Point p;
            int z=imp.getNSlices();
            int zlice=imp.getSlice()-1;
            int zcoord=(int)Math.round(arat*zlice);
            if (flipXZ) zcoord = (int)Math.round(arat*(z-zlice));

            GeneralPath path = new GeneralPath();

            if(xzMap.containsKey(entry.getKey())) {
                ImagePlus xz_image = xzMap.get(entry.getKey());
                //IJ.log("here");
                ImageCanvas xzCanvas = xz_image.getCanvas();
                p=new Point (x, zcoord);
                
                drawCross(xz_image, p, path);
                xz_image.setOverlay(path, color, new BasicStroke(2));
            }


            if(yzMap.containsKey(entry.getKey())) {
                ImagePlus yz_image = yzMap.get(entry.getKey());

                if (rotateYZ) {
                    if (flipXZ)
                        zcoord=(int)Math.round(brat*(z-zlice));
                    else
                        zcoord=(int)Math.round(brat*(zlice));
                    p=new Point (y, zcoord);
                } else {
                    zcoord=(int)Math.round(arat*zlice);
                    p=new Point (zcoord, y);
                }
                path = new GeneralPath();
                drawCross(yz_image, p, path);
                yz_image.setOverlay(path, color, new BasicStroke(2));
                IJ.showStatus(imp.getLocationAsString(crossLoc.x, crossLoc.y));
            }
            
        }
        
    }

    /**
    *   @method update magnification for given image so that updating zoom in/out properly. 
    */
    private void updateMagnification(int id, int x, int y) {
        ImagePlus imp = imageMap.get(id);
        ImageWindow win = imp.getWindow();
        
        int z = imp.getSlice()-1;
        double arat = az/ax;
        int zcoord=(int)(arat*z);
        double magnification= win.getCanvas().getMagnification();

        if(xzMap.containsKey(id)) {
            ImagePlus xz_image = xzMap.get(id);

        
            ImageWindow xz_win = xz_image.getWindow();
            if (xz_win==null) return;
            ImageCanvas xz_ic = xz_win.getCanvas();
            double xz_mag = xz_ic.getMagnification();
            
            if (flipXZ) zcoord=(int)(arat*(imp.getNSlices()-z));
            while (xz_mag<magnification) {
                xz_ic.zoomIn(xz_ic.screenX(x), xz_ic.screenY(zcoord));
                xz_mag = xz_ic.getMagnification();
            }
            while (xz_mag>magnification) {
                xz_ic.zoomOut(xz_ic.screenX(x), xz_ic.screenY(zcoord));
                xz_mag = xz_ic.getMagnification();
            }
        }

        if(yzMap.containsKey(id)) {
            ImagePlus yz_image = yzMap.get(id);
            ImageWindow yz_win = yz_image.getWindow();
            if (yz_win==null) return;
            ImageCanvas yz_ic = yz_win.getCanvas();
            double yz_mag = yz_ic.getMagnification();
            zcoord = (int)(arat*z);
            while (yz_mag<magnification) {
                //IJ.log(magnification+"  "+yz_mag+"  "+zcoord+"  "+y+"  "+x);
                yz_ic.zoomIn(yz_ic.screenX(zcoord), yz_ic.screenY(y));
                yz_mag = yz_ic.getMagnification();
            }
            while (yz_mag>magnification) {
                yz_ic.zoomOut(yz_ic.screenX(zcoord), yz_ic.screenY(y));
                yz_mag = yz_ic.getMagnification();
            }
        }
        
    }

    /**
    *   @method update views for given xy image based on the cliked point.
    */
	private void updateViews(int id, Point p) {
        ImagePlus imp = imageMap.get(id);
        ImageProcessor f2 = fp2.get(id);

		ImageStack is = imp.getStack();

        if (fp1.get(id) ==null) return;

        double arat=az/ax;
        int width2 = fp1.get(id).getWidth();
        int height2 = (int)Math.round(fp1.get(id).getHeight()*az);

        if(xzMap.containsKey(id)) {
            ImagePlus xz_image = xzMap.get(id);

            updateXZView(id,p,is);
            
            if (width2!=fp1.get(id).getWidth()||height2!=fp1.get(id).getHeight()) {
                fp1.get(id).setInterpolate(true);
                ImageProcessor sfp1=fp1.get(id).resize(width2, height2);
                if (!rgb) sfp1.setMinAndMax(min, max);
                xz_image.setProcessor("XZ "+p.y, sfp1);
            } else {
                if (!rgb) fp1.get(id).setMinAndMax(min, max);
                xz_image.setProcessor("XZ "+p.y, fp1.get(id));
            }

            if (xz_image.getWindow()==null) {
                xz_image.show();
                ImageCanvas ic = xz_image.getCanvas();
               // ic.addKeyListener(this);
                //ic.addMouseListener(this);
                //ic.addMouseMotionListener(this);
                ic.setCustomRoi(true);
                //xz_image.getWindow().addMouseWheelListener(this);
                int xzID = xz_image.getID();
            } else {
                ImageCanvas ic = xz_image.getWindow().getCanvas();
                //ic.addMouseListener(this);
                //ic.addMouseMotionListener(this);
                ic.setCustomRoi(true);
            }
        }
            
        
        if(yzMap.containsKey(id)) {
            ImagePlus yz_image = yzMap.get(id);
            if (rotateYZ)
                updateYZView(id, p, is);
            
            else
                 updateZYView(id, p, is);
                    
            width2 = (int)Math.round(f2.getWidth()*az);
            height2 = f2.getHeight();
            String title = "YZ ";
            if (rotateYZ) {
                width2 = f2.getWidth();
                height2 = (int)Math.round(f2.getHeight()*az);
                title = "ZY ";
            }

            //IJ.log("updateViews "+width2+" "+height2+" "+arat+" "+ay+" "+f2);
            if (width2!=f2.getWidth()||height2!=f2.getHeight()) {
                f2.setInterpolate(true);
                ImageProcessor sf2=f2.resize(width2, height2);
                if (!rgb) sf2.setMinAndMax(min, max);
                yz_image.setProcessor(title+p.x, sf2);
            } else {
                if (!rgb) f2.setMinAndMax(min, max);
                yz_image.setProcessor(title+p.x, f2);
            }

            calibrate(id);
            if (yz_image.getWindow()==null) {
                yz_image.show();
                ImageCanvas ic = yz_image.getCanvas();
                //ic.addKeyListener(this);
                ic.setCustomRoi(true);
                //yz_image.getWindow().addMouseWheelListener(this);
                int yzID = yz_image.getID();
            } else {
                ImageCanvas ic = yz_image.getWindow().getCanvas();
                //ic.addMouseListener(this);
                //ic.addMouseMotionListener(this);
                ic.setCustomRoi(true);
            }
        }
         
    }

    /**
    *   @method update views for given xz image based on the cliked point.
    */
    void updateXZView(int id, Point p, ImageStack is) {
        int width= is.getWidth();
        int size=is.getSize();
        ImageProcessor ip=is.getProcessor(1);
        
        int y=p.y;
        // XZ
        if (ip instanceof ShortProcessor) {
            short[] newpix=new short[width*size];
            for (int i=0; i<size; i++) { 
                Object pixels=is.getPixels(i+1);
                if (flipXZ)
                    System.arraycopy(pixels, width*y, newpix, width*(size-i-1), width);
                else
                    System.arraycopy(pixels, width*y, newpix, width*i, width);
            }
            fp1.get(id).setPixels(newpix);
            return;
        }
        
        if (ip instanceof ByteProcessor) {
            byte[] newpix=new byte[width*size];
            for (int i=0;i<size; i++) { 
                Object pixels=is.getPixels(i+1);
                if (flipXZ)
                    System.arraycopy(pixels, width*y, newpix, width*(size-i-1), width);
                else
                    System.arraycopy(pixels, width*y, newpix, width*i, width);
            }
            fp1.get(id).setPixels(newpix);
            return;
        }
        
        if (ip instanceof FloatProcessor) {
            float[] newpix=new float[width*size];
            for (int i=0; i<size; i++) { 
                Object pixels=is.getPixels(i+1);
                if (flipXZ)
                    System.arraycopy(pixels, width*y, newpix, width*(size-i-1), width);
                else
                    System.arraycopy(pixels, width*y, newpix, width*i, width);
            }
            fp1.get(id).setPixels(newpix);
            return;
        }
        
        if (ip instanceof ColorProcessor) {
            int[] newpix=new int[width*size];
            for (int i=0;i<size; i++) { 
                Object pixels=is.getPixels(i+1);
                if (flipXZ)
                    System.arraycopy(pixels, width*y, newpix, width*(size-i-1), width);
                else
                    System.arraycopy(pixels, width*y, newpix, width*i, width);
            }
            fp1.get(id).setPixels(newpix);
            return;
        }
        
    }

    /**
    *   @method update views for given xz image based on the cliked point.
    */
    void updateYZView(int id, Point p, ImageStack is) {
        ImageProcessor f2 = fp2.get(id);

        int width= is.getWidth();
        int height=is.getHeight();
        int ds=is.getSize();
        ImageProcessor ip=is.getProcessor(1);
        int x=p.x;
        
        if (ip instanceof FloatProcessor) {
            float[] newpix=new float[ds*height];
            for (int i=0;i<ds; i++) { 
                float[] pixels= (float[]) is.getPixels(i+1);//toFloatPixels(pixels);
                for (int j=0;j<height;j++)
                    newpix[(ds-i-1)*height + j] = pixels[x + j* width];
            }
            f2.setPixels(newpix);
        }
        
        if (ip instanceof ByteProcessor) {
            byte[] newpix=new byte[ds*height];
            for (int i=0;i<ds; i++) { 
                byte[] pixels= (byte[]) is.getPixels(i+1);//toFloatPixels(pixels);
                for (int j=0;j<height;j++)
                    newpix[(ds-i-1)*height + j] = pixels[x + j* width];
            }
            f2.setPixels(newpix);
        }
        
        if (ip instanceof ShortProcessor) {
            short[] newpix=new short[ds*height];
            for (int i=0;i<ds; i++) { 
                short[] pixels= (short[]) is.getPixels(i+1);//toFloatPixels(pixels);
                for (int j=0;j<height;j++)
                    newpix[(ds-i-1)*height + j] = pixels[x + j* width];
            }
            f2.setPixels(newpix);
        }
        
        if (ip instanceof ColorProcessor) {
            int[] newpix=new int[ds*height];
            for (int i=0;i<ds; i++) { 
                int[] pixels= (int[]) is.getPixels(i+1);//toFloatPixels(pixels);
                for (int j=0;j<height;j++)
                    newpix[(ds-i-1)*height + j] = pixels[x + j* width];
            }
            f2.setPixels(newpix);
        }
        if (!flipXZ) f2.flipVertical();
        
    }

    /**
    *   @method update views for given yz image based on the cliked point.
    */
    void updateZYView(int id, Point p, ImageStack is) {
        ImageProcessor f2 = fp2.get(id);
        int width= is.getWidth();
        int height=is.getHeight();
        int ds=is.getSize();
        ImageProcessor ip=is.getProcessor(1);
        int x=p.x;
        
        if (ip instanceof FloatProcessor) {
            float[] newpix=new float[ds*height];
            for (int i=0;i<ds; i++) { 
                float[] pixels= (float[]) is.getPixels(i+1);//toFloatPixels(pixels);
                for (int y=0;y<height;y++)
                    newpix[i + y*ds] = pixels[x + y* width];
            }
            f2.setPixels(newpix);
        }
        
        if (ip instanceof ByteProcessor) {
            byte[] newpix=new byte[ds*height];
            for (int i=0;i<ds; i++) { 
                byte[] pixels= (byte[]) is.getPixels(i+1);//toFloatPixels(pixels);
                for (int y=0;y<height;y++)
                    newpix[i + y*ds] = pixels[x + y* width];
            }
            f2.setPixels(newpix);
        }
        
        if (ip instanceof ShortProcessor) {
            short[] newpix=new short[ds*height];
            for (int i=0;i<ds; i++) { 
                short[] pixels= (short[]) is.getPixels(i+1);//toFloatPixels(pixels);
                for (int y=0;y<height;y++)
                    newpix[i + y*ds] = pixels[x + y* width];
            }
            f2.setPixels(newpix);
        }
        
        if (ip instanceof ColorProcessor) {
            int[] newpix=new int[ds*height];
            for (int i=0;i<ds; i++) { 
                int[] pixels= (int[]) is.getPixels(i+1);//toFloatPixels(pixels);
                for (int y=0;y<height;y++)
                    newpix[i + y*ds] = pixels[x + y* width];
            }
            f2.setPixels(newpix);
        }
        
    }

    public void componentHidden(ComponentEvent e) {
    }

    public void componentMoved(ComponentEvent e) {
        Iterator<Map.Entry<Integer, ImagePlus>> entries = imageMap.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, ImagePlus> entry = entries.next();
            int id = entry.getKey();
            
            updateMagnification(id, crossLoc.x, crossLoc.y);
            arrangeWindows(id);
        }
    }

    public void componentResized(ComponentEvent e) {
       Iterator<Map.Entry<Integer, ImagePlus>> entries = imageMap.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, ImagePlus> entry = entries.next();
            int id = entry.getKey();
            
            updateMagnification(id, crossLoc.x, crossLoc.y);
            arrangeWindows(id);
        }
                   
    }

    public void componentShown(ComponentEvent e) {
    }

	public  void adjustmentValueChanged(AdjustmentEvent e) {
    }

	public void imageClosed(ImagePlus imp) {
        //imp.getWindow().removeComponentListener(this);
        Iterator<Map.Entry<Integer, ImagePlus>> entries;

        // remove from the map
        if(imageMap.containsValue(imp)) {
            entries = imageMap.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<Integer, ImagePlus> entry = entries.next();
                if(imp == entry.getValue()){
                    entries.remove();
                    return;
                }
            }
        }

        else if(xzMap.containsValue(imp)) {
            entries = xzMap.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<Integer, ImagePlus> entry = entries.next();
                if(imp == entry.getValue()){
                    entries.remove();
                    return;
                }
            }
        }

        else if(yzMap.containsValue(imp)) {
            entries = yzMap.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<Integer, ImagePlus> entry = entries.next();
                if(imp == entry.getValue()){
                    entries.remove();
                    return;
                }
            }
        }
	}

    public void imageOpened(ImagePlus imp) {
    }

    public void imageUpdated(ImagePlus imp) {
        /* When scroll bar is scrolled. */
        if(imp.getSlice() != 1 && prevX == crossLoc.x && prevY == crossLoc.y) {
            double arat=az/ax;
            double brat=az/ay;
            Point p=crossLoc;
            // if (p.y>=height) p.y=height-1;
            // if (p.x>=width) p.x=width-1;
            if (p.x<0) p.x=0;
            if (p.y<0) p.y=0;
            updateCrosses(p.x, p.y, arat, brat);
        }

        prevX = crossLoc.x;
        prevY = crossLoc.y;
    }

	public void mouseClicked(MouseEvent e) {
    }

    public void mouseMoved(MouseEvent e) {
    }

    public void mouseDragged(MouseEvent e) {
    }

    public void mouseEntered(MouseEvent e) {
    }

    public void mouseExited(MouseEvent e) {
    }   
	
	public void mouseReleased(MouseEvent e) {
	}

	public void mousePressed(MouseEvent e) {
        ImageCanvas ic = (ImageCanvas) e.getSource();
        Iterator<Map.Entry<Integer, ImagePlus>> entries;

        TCursor tc = new TCursor();
        tc = getTranslateCursor(e);
        crossLoc = tc.p;
        

        /* Update slice of main image if it's required. */
        if(tc.slice != -1) {
            entries = imageMap.entrySet().iterator();

            while (entries.hasNext()) {
                Map.Entry<Integer, ImagePlus> entry = entries.next();
        
                entry.getValue().setSlice(tc.slice);
            }
        }

        /* Propagate to all the images and get a new image of xz and yz. */
        entries = imageMap.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, ImagePlus> entry = entries.next();
            int id = entry.getKey();
            ImagePlus imp = entry.getValue();
            updateViews(id, crossLoc);

            GeneralPath path = new GeneralPath();
            drawCross(imp, crossLoc, path);
            imp.setOverlay(path, Roi.getColor(), new BasicStroke(2));

            double arat=az/ax;
            double brat=az/ay;
            Point p=crossLoc;
            // if (p.y>=height) p.y=height-1;
            // if (p.x>=width) p.x=width-1;
            if (p.x<0) p.x=0;
            if (p.y<0) p.y=0;
            updateCrosses(p.x, p.y, arat, brat);
        }


    }

    public void windowActivated(WindowEvent e) {
    }

    public void windowClosed(WindowEvent e) {
    }

    public void windowClosing(WindowEvent e) {
    }

    public void windowDeactivated(WindowEvent e) {
    }

    public void windowDeiconified(WindowEvent e) {
    }

    public void windowIconified(WindowEvent e) {
    }

    public void windowOpened(WindowEvent e) {
    }
}
