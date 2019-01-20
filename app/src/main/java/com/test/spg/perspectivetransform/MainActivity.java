package com.test.spg.perspectivetransform;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.utils.Converters;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends Activity implements View.OnClickListener, EasyPermissions.PermissionCallbacks{

    private static final String TAG = "MainActivity";

    // 借助 Easypermissions 来显示打开应用后动态获取权限：相机和存储
    private String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE};

    private Button btn_takePhoto;
    private Button btn_cut;
    private Button btn_album;
    private Button btn_clear;
    private Button btn_function;
    private ImageView ivImage;
    private TextView text_count;
    private File cameraSavePath;
    private Uri uri;

    // 项目的目标是实现透视变化，corners存储目标主体的四个角坐标，targets为转换后图像的四个角坐标
    private List<Point> corners;
    private List<Point> targets;

    // 单击图像获取的像素坐标
    private double x;
    private double y;
    // 点击量计数器
    private int count = 0;
    // 获取手机屏幕宽度像素
    private int phone_width;
    // 获取图像
    private double pic_height;
    private double pic_width;

    // 透视变换的原图像，变换矩阵，生成图像
    private Mat img;
    private Mat trans;
    private Mat proj;

    LinearLayout ll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initLoadOpenCV();// 加载OpenCV
        getPermission();// 获取所需权限
        getPhoneInfo();// 获取手机信息，这里只取手机横向分辨率，用于坐标计算转换
        init();// 各组件的初始化
    }

    /**
     * 加载OpenCV
     */
    private void initLoadOpenCV() {
        boolean success = OpenCVLoader.initDebug();
        if (success) {
            Log.i(TAG, "OpenCV Libraries loaded...");
        } else {
            Toast.makeText(this.getApplicationContext(), "WARNING: Could not load OpenCV Libraries",Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 组件初始化
     */
    @SuppressLint("ClickableViewAccessibility")
    private void init(){
        btn_takePhoto = findViewById(R.id.btn_takePhoto);
        btn_cut = findViewById(R.id.btn_cut);
        btn_album = findViewById(R.id.btn_album);
        btn_clear = findViewById(R.id.btn_clear);
        btn_function = findViewById(R.id.btn_function);
        ivImage = findViewById(R.id.image);
        text_count = findViewById(R.id.text_count);
        btn_takePhoto.setOnClickListener(this);
        btn_cut.setOnClickListener(this);
        btn_album.setOnClickListener(this);
        btn_clear.setOnClickListener(this);
        btn_function.setOnClickListener(this);
        ll = findViewById(R.id.ll);

        corners = new ArrayList<>();
        targets = new ArrayList<>();

        img = new Mat();
        proj = new Mat();

        // 获取图像的宽高
        Bitmap bitmap = ((BitmapDrawable)ivImage.getDrawable()).getBitmap();
        //Toast.makeText(this,"Height: "+bitmap.getHeight()+" Weight: "+bitmap.getWidth(),Toast.LENGTH_SHORT).show();
        pic_width = bitmap.getWidth();
        pic_height = bitmap.getHeight();

        // 设置存储图像的路径
        cameraSavePath = new File(Environment.getExternalStorageDirectory().getPath() +
                "/" + System.currentTimeMillis() + ".jpg");

        // 给ImageView设置点击监听，获取点击处的坐标
        ivImage.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                switch (motionEvent.getAction()){
                    case MotionEvent.ACTION_DOWN: {
                        if (count < 5){
                            x = convert_xy(motionEvent.getX());//获取点击处的x坐标并转换
                            y = convert_xy(motionEvent.getY());//获取点击处的y坐标并转换
                            corners.add(new Point(x,y));
                            count++;
                            updataInfo();
                        } else {
                            clearPoint();
                        }
                    }
                }
                return false;
            }
        });
    }

    /**
     * 各个按钮的点击功能实现
     * @param v
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.btn_takePhoto: {
                Toast.makeText(this, "拍照", Toast.LENGTH_SHORT).show();
                takePhoto();
                break;
            }
            case R.id.btn_cut: {
                perspectiveTransform();
                break;
            }
            case R.id.btn_album: {
                openAlbum();
                clearPoint();
                break;
            }
            case R.id.btn_clear:{
                clearPoint();
                break;
            }
            case R.id.btn_function:{
                this.recreate();// 重启当前页面
                break;
            }
            default:
                break;
        }
    }

    /**
     * 清除当前已经标记的所有点
     */
    private void clearPoint(){
        corners.clear();
        count = 0;
        text_count.setText("");
    }

    // 进入相册选择图片
    private void openAlbum() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, 2);
    }

    /**
     * 主方法，实现透视变换
     */
    private void perspectiveTransform(){
        Bitmap bitmap = ((BitmapDrawable)ivImage.getDrawable()).getBitmap();
        Utils.bitmapToMat(bitmap, img);
        //Toast.makeText(this, "Width: "+img.cols()+"Height: "+img.rows(),Toast.LENGTH_SHORT).show();
        targets = new ArrayList<>();

        if (corners.size() == 4){
            double maxWidth = Distense(corners.get(0),corners.get(2));
            double maxHeight = Math.sqrt(2)*maxWidth;

            targets.add(new Point(0,0));
            targets.add(new Point(maxWidth-1,0));
            targets.add(new Point(0,maxHeight-1));
            targets.add(new Point(maxWidth-1,maxHeight-1));

            trans=Imgproc.getPerspectiveTransform(Converters.vector_Point2f_to_Mat(corners), Converters.vector_Point2f_to_Mat(targets));

            Imgproc.warpPerspective(img, proj, trans, new Size(maxWidth,maxHeight));

            bitmap = Bitmap.createBitmap(proj.width(),proj.height(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(proj,bitmap);
            ImageView imageView = findViewById(R.id.image);
            imageView.setImageBitmap(bitmap);
            MediaStore.Images.Media.insertImage(getContentResolver(), bitmap, "test", "description");
            Toast.makeText(this,"图像已保存",Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this,"请选择四个点，按照左上右上左下右下的顺序",Toast.LENGTH_SHORT).show();
        }

    }

    /**
     * 获取当前手机屏幕的宽 phone_width
     */
    private void getPhoneInfo(){
        Display display = getWindowManager().getDefaultDisplay();
        android.graphics.Point outSize = new android.graphics.Point();
        display.getSize(outSize);
        phone_width = outSize.x;
    }

    /**
     * 因为点击处的坐标为手机屏幕的坐标，原点在手机屏幕的左上角，而不是图像上点的坐标，所以这里做一个转换
     * @param x
     * @return
     */
    private double convert_xy(double x){
        Bitmap bitmap = ((BitmapDrawable)ivImage.getDrawable()).getBitmap();
        return (x * bitmap.getWidth() / phone_width);
    }

    /**
     * 更新当前点击处的信息并显示出来
     */
    public void updataInfo(){
        String tt_count = "";
        for (int i = 0; i < count; i++) {
            tt_count += "第"+(i+1)+"个点: "+ corners.get(i).x +", "+ corners.get(i).y+"\n";
        }
        text_count.setText(tt_count);
    }

    /**
     * 调用系统相机并拍照
     */
    private void takePhoto(){
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(MainActivity.this, "com.test.spg.perspectivetransform.fileprovider", cameraSavePath);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(cameraSavePath);
        }
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        MainActivity.this.startActivityForResult(intent, 1);
    }

    /**
     * 将拍照和调用相册得到的图像放在ImageView中等待做后一步的处理
     * @param requestCode
     * @param resultCode
     * @param data
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        String photoPath;
        if (requestCode == 1 && resultCode == RESULT_OK) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                photoPath = String.valueOf(cameraSavePath);
            } else {
                photoPath = uri.getEncodedPath();
            }
            Log.d("拍照返回图片路径:", photoPath);
            Glide.with(MainActivity.this).load(photoPath).into(ivImage);
        } else if (requestCode == 2 && resultCode == RESULT_OK) {
            photoPath = getPhotoFromPhotoAlbum.getRealPathFromUri(this, data.getData());
            Glide.with(MainActivity.this).load(photoPath).into(ivImage);
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    //获取权限
    private void getPermission() {
        if (EasyPermissions.hasPermissions(this, permissions)) {
            //已经打开权限
            Toast.makeText(this, "已经申请相关权限", Toast.LENGTH_SHORT).show();
        } else {
            //没有打开相关权限、申请权限
            EasyPermissions.requestPermissions(this, "需要获取您的相册、相机使用权限", 1, permissions);
        }
    }

    //动态获取权限
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    // 动态获取权限回调函数
    @Override
    public void onPermissionsGranted(int requestCode, @NonNull List<String> perms) {
        Toast.makeText(this, "权限获取成功", Toast.LENGTH_SHORT).show();
    }

    // 动态获取权限回调函数
    @Override
    public void onPermissionsDenied(int requestCode, @NonNull List<String> perms) {
        Toast.makeText(this, "请同意相关权限，否则功能无法使用", Toast.LENGTH_SHORT).show();
    }

    // 计算corners中两点的距离
    private double Distense(Point point1, Point point2){
        double x = Math.abs(point1.x - point2.x);
        double y = Math.abs(point1.y - point2.y);
        return Math.sqrt(x*x+y*y);
    }
}
